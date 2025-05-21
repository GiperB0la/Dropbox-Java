package Client;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

import Protocol.*;

enum ReadState {
    READING_HEADER,
    READING_JSON,
    READING_BINARY
}

public class Client {
    private final String host;
    private final int port;

    private Selector selector;
    private SocketChannel channel;
    private final Queue<ByteBuffer> outgoing = new ArrayDeque<>();
    private boolean writing = false;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(15);
    private ByteBuffer jsonBuffer;
    private ByteBuffer binaryBuffer;
    private ReadState state = ReadState.READING_HEADER;
    private int jsonLength = 0;
    private long binaryLength = 0;
    private final String pathStorage = "D:\\Java\\Projects\\Dropbox\\ClientStorage\\";
    private Consumer<List<String>> listCallback;

    public Client(String host, int port) {
        this.host = host;
        this.port = port;
    }

    private void connect() throws IOException {
        selector = Selector.open();

        channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(host, port));
        channel.register(selector, SelectionKey.OP_CONNECT);
    }

    public void start() {
        while (true) {
            try {
                connect();

                System.out.println("[*] Connecting to server...");

                while (channel.isOpen()) {
                    selector.select();

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (key.isConnectable()) {
                            handleConnect(key);
                        }

                        if (key.isWritable()) {
                            handleWrite(key);
                        }

                        if (key.isReadable()) {
                            handleRead(key);
                        }
                    }
                }

            } catch (IOException e) {
                System.out.println("[-] Connection error: " + e.getMessage());
            }

            System.out.println("[*] Reconnecting in 2 seconds...");
            try {
                Thread.sleep(2000);
            }
            catch (InterruptedException ignored) {}
        }
    }

    private void handleConnect(SelectionKey key) throws IOException {
        if (channel.finishConnect()) {
            System.out.println("[+] Connected to server");
            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        if (state == ReadState.READING_HEADER) {
            int read = channel.read(headerBuffer);
            if (read == -1) {
                System.out.println("[-] Server closed connection");
                channel.close();
                key.cancel();
                return;
            }

            if (headerBuffer.remaining() == 0) {
                headerBuffer.flip();
                short magic = headerBuffer.getShort();
                if (magic != ProtocolWriter.MAGIC) {
                    System.out.println("[-] Invalid magic, disconnecting");
                    return;
                }

                byte opCode = headerBuffer.get();
                Operation op = Operation.fromCode(opCode);

                jsonLength = headerBuffer.getInt();
                binaryLength = headerBuffer.getLong();
                headerBuffer.clear();

                jsonBuffer = ByteBuffer.allocate(jsonLength);
                if (jsonLength == 0) {
                    if (binaryLength > 0) {
                        state = ReadState.READING_BINARY;
                    }
                    else {
                        state = ReadState.READING_HEADER;
                    }
                }
                else {
                    state = ReadState.READING_JSON;
                }
            }
        }

        if (state == ReadState.READING_JSON) {
            int read = channel.read(jsonBuffer);
            if (read == -1) {
                System.out.println("[-] Server closed connection");
                channel.close();
                key.cancel();
                return;
            }

            if (!jsonBuffer.hasRemaining()) {
                jsonBuffer.flip();
                if (binaryLength > 0) {
                    state = ReadState.READING_BINARY;
                }
                else {
                    handlePacket(jsonBuffer, null);
                    state = ReadState.READING_HEADER;
                }
            }
        }

        if (state == ReadState.READING_BINARY) {
            if (binaryBuffer == null) {
                binaryBuffer = ByteBuffer.allocate((int) binaryLength);
            }

            int read = channel.read(binaryBuffer);
            if (read == -1) {
                System.out.println("[-] Server closed connection");
                channel.close();
                key.cancel();
                return;
            }

            if (!binaryBuffer.hasRemaining()) {
                binaryBuffer.flip();
                handlePacket(jsonBuffer, binaryBuffer);

                jsonBuffer = null;
                binaryBuffer = null;
                jsonLength = 0;
                binaryLength = 0;
                state = ReadState.READING_HEADER;
            }
        }
    }

    private void handlePacket(ByteBuffer jsonBuf, ByteBuffer binBuf) {
        String msg = new String(jsonBuf.array(), StandardCharsets.UTF_8);
        JsonProtocol reply = JsonProtocol.fromJson(msg);
        System.out.println("\n[<] Got operation: " + reply.operation);

        switch (reply.operation) {
            case LIST_REPLY -> {
                if (reply.files != null) {
                    System.out.println("[✓] File list received (" + reply.files.size() + " files):");
                    for (String file : reply.files) {
                        System.out.println(" - " + file);
                    }
                    if (listCallback != null) {
                        listCallback.accept(reply.files);
                    }
                }
                else {
                    System.out.println("[-] Empty file list or missing field");
                }
            }

            case UPLOAD_OK -> {
                System.out.println("[✓] Upload result:");
                System.out.println("Status: " + reply.status);
                System.out.println("Message: " + reply.message);
            }

            case DELETE_OK -> {
                System.out.println("[✓] Delete result:");
                System.out.println("Status: " + reply.status);
                System.out.println("Message: " + reply.message);
            }

            case DOWNLOAD_REPLY -> {
                if (binBuf == null || reply.fileName == null || reply.size <= 0) {
                    System.out.println("[-] Invalid DOWNLOAD_REPLY");
                    return;
                }

                File saveFile = new File(pathStorage + reply.fileName);
                new File(pathStorage).mkdirs();

                try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                    fos.write(binBuf.array(), 0, binBuf.remaining());
                    System.out.println("[✓] File downloaded: " + saveFile.getAbsolutePath());
                    System.out.println("Size: " + reply.size + " bytes");
                }
                catch (IOException e) {
                    System.out.println("[-] Failed to save file: " + e.getMessage());
                }
            }

            case ERROR -> {
                System.out.println("[✗] Server error:");
                System.out.println("Status: " + reply.status);
                System.out.println("Message: " + reply.message);
            }

            default -> {
                System.out.println("[-] Unknown operation: " + reply.operation);
            }
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        while (!outgoing.isEmpty()) {
            ByteBuffer buf = outgoing.peek();
            channel.write(buf);
            if (buf.hasRemaining()) return;
            outgoing.poll();
        }

        key.interestOps(SelectionKey.OP_READ);
        writing = false;
    }

    private void queueToSend(ByteBuffer buf) {
        outgoing.add(buf);
        if (!writing) {
            channel.keyFor(selector).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            writing = true;
            selector.wakeup();
        }
    }

    public void sendList() {
        JsonProtocol json = new JsonProtocol();
        json.operation = Operation.LIST;
        queueToSend(ProtocolWriter.buildPacket(Operation.LIST, JsonProtocol.toJson(json), null));
    }

    public void sendDelete(String fileName) {
        JsonProtocol json = new JsonProtocol();
        json.operation = Operation.DELETE;
        json.fileName = fileName;
        queueToSend(ProtocolWriter.buildPacket(Operation.DELETE, JsonProtocol.toJson(json), null));
    }

    public void sendDownload(String fileName) {
        JsonProtocol json = new JsonProtocol();
        json.operation = Operation.DOWNLOAD;
        json.fileName = fileName;
        queueToSend(ProtocolWriter.buildPacket(Operation.DOWNLOAD, JsonProtocol.toJson(json), null));
    }

    public void sendUpload(String filePath) {
        try {
            File file = new File(filePath);
            byte[] data = Files.readAllBytes(file.toPath());
            JsonProtocol json = new JsonProtocol();
            json.operation = Operation.UPLOAD;
            json.fileName = file.getName();
            json.size = data.length;
            String jsonS = JsonProtocol.toJson(json);
            queueToSend(ProtocolWriter.buildPacket(Operation.UPLOAD, JsonProtocol.toJson(json), data));
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setListCallback(Consumer<List<String>> callback) {
        this.listCallback = callback;
    }
}