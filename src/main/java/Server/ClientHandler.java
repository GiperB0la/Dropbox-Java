package Server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import Protocol.*;

enum ReadState {
    READING_HEADER,
    READING_JSON,
    READING_BINARY
}

public class ClientHandler {
    private final SocketChannel channel;
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(15);
    private ByteBuffer jsonBuffer;
    private ByteBuffer binaryBuffer;
    private ReadState state = ReadState.READING_HEADER;
    private int jsonLength = 0;
    private long binaryLength = 0;
    private final String pathStorage = "D:\\Java\\Projects\\Dropbox\\Storage\\";

    private final Selector selector;
    private final Queue<ByteBuffer> outgoing = new ArrayDeque<>();
    private boolean writing = false;

    public ClientHandler(SocketChannel channel, Selector selector) throws IOException {
        this.channel = channel;
        this.selector = selector;
    }

    public void read() throws IOException {
        if (state == ReadState.READING_HEADER) {
            int read = channel.read(headerBuffer);
            if (read == -1) {
                close();
                return;
            }

            if (headerBuffer.remaining() == 0) {
                headerBuffer.flip();
                short magic = headerBuffer.getShort();
                if (magic != ProtocolWriter.MAGIC) {
                    System.out.println("[-] Invalid magic, disconnecting");
                    close();
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
                close();
                return;
            }

            if (!jsonBuffer.hasRemaining()) {
                jsonBuffer.flip();
                if (binaryLength > 0) {
                    state = ReadState.READING_BINARY;
                }
                else {
                    handlePacket(jsonBuffer, binaryBuffer);
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
                close();
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

    public void handleWrite() throws IOException {
        while (!outgoing.isEmpty()) {
            ByteBuffer buf = outgoing.peek();
            channel.write(buf);
            if (buf.hasRemaining()) return;
            outgoing.poll();
        }

        writing = false;
        SelectionKey key = channel.keyFor(selector);
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    private void handlePacket(ByteBuffer jsonBuf, ByteBuffer binBuf) throws IOException {
        String msg = new String(jsonBuf.array(), StandardCharsets.UTF_8);
        JsonProtocol json = JsonProtocol.fromJson(msg);
        System.out.println("[<] Got operation: " + json.operation);

        switch (json.operation) {
            case LIST -> {
                sendList();
            }

            case UPLOAD -> {
                JsonProtocol reply = new JsonProtocol();
                reply.operation = Operation.UPLOAD_OK;

                if (binBuf == null || json.fileName == null) {
                    reply.status = "FAIL";
                    reply.message = "Missing file or metadata";
                    sendReply(ProtocolWriter.buildPacket(reply.operation, JsonProtocol.toJson(reply), null));
                    return;
                }

                File outFile = new File(pathStorage + json.fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(binBuf.array(), 0, binBuf.remaining());
                    reply.status = "OK";
                    reply.message = "File uploaded: " + json.fileName;
                    System.out.println("[+] Saved file: " + outFile.getName());
                }
                catch (IOException e) {
                    reply.status = "FAIL";
                    reply.message = "I/O error: " + e.getMessage();
                }

                sendReply(ProtocolWriter.buildPacket(reply.operation, JsonProtocol.toJson(reply), null));
            }

            case DELETE -> {
                JsonProtocol reply = new JsonProtocol();
                reply.operation = Operation.DELETE_OK;

                if (json.fileName == null) {
                    reply.status = "FAIL";
                    reply.message = "Filename is missing";
                    sendReply(ProtocolWriter.buildPacket(reply.operation, JsonProtocol.toJson(reply), null));
                    return;
                }

                File file = new File(pathStorage + json.fileName);
                if (file.exists() && file.isFile()) {
                    boolean deleted = file.delete();
                    if (deleted) {
                        reply.status = "OK";
                        reply.message = "File deleted: " + json.fileName;
                    } else {
                        reply.status = "FAIL";
                        reply.message = "Failed to delete file";
                    }
                } else {
                    reply.status = "NOT_FOUND";
                    reply.message = "File does not exist";
                }

                sendReply(ProtocolWriter.buildPacket(reply.operation, JsonProtocol.toJson(reply), null));
            }

            case DOWNLOAD -> {
                if (json.fileName == null) {
                    sendError("Filename is missing", Operation.ERROR);
                    return;
                }

                File file = new File(pathStorage + json.fileName);
                if (!file.exists() || !file.isFile()) {
                    sendError("File not found: " + json.fileName, Operation.ERROR);
                    return;
                }

                byte[] fileBytes = Files.readAllBytes(file.toPath());

                JsonProtocol reply = new JsonProtocol();
                reply.operation = Operation.DOWNLOAD_REPLY;
                reply.status = "OK";
                reply.message = "File sent";
                reply.fileName = file.getName();
                reply.size = fileBytes.length;

                ByteBuffer packet = ProtocolWriter.buildPacket(reply.operation, JsonProtocol.toJson(reply), fileBytes);
                sendReply(packet);
            }

            default -> sendError("Unsupported operation: " + json.operation, Operation.ERROR);
        }
    }

    private void sendReply(ByteBuffer packet) throws IOException {
        outgoing.add(packet);
        if (!writing) {
            SelectionKey key = channel.keyFor(selector);
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            writing = true;
            selector.wakeup();
        }
    }

    public void sendList() throws IOException {
        JsonProtocol reply = new JsonProtocol();
        reply.operation = Operation.LIST_REPLY;
        reply.status = "OK";
        reply.message = "File list retrieved";

        File[] files = new File(pathStorage).listFiles();
        reply.files = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    reply.files.add(file.getName());
                }
            }
        }

        sendReply(ProtocolWriter.buildPacket(Operation.LIST_REPLY, JsonProtocol.toJson(reply), null));
    }

    private void sendError(String message, Operation op) throws IOException {
        JsonProtocol error = new JsonProtocol();
        error.operation = op;
        error.status = "FAIL";
        error.message = message;

        sendReply(ProtocolWriter.buildPacket(op, JsonProtocol.toJson(error), null));
        System.out.println("[-] " + message);
    }

    public void close() throws IOException {
        channel.close();
    }
}