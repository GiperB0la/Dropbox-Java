package Server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.*;

public class Server {
    private final int port;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    public Server(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        selector = Selector.open();

        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("[*] Server started on port " + port);

        while (true) {
            selector.select();

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) {
                    continue;
                }

                if (key.isAcceptable()) {
                    SocketChannel clientChannel = serverChannel.accept();
                    clientChannel.configureBlocking(false);
                    ClientHandler handler = new ClientHandler(clientChannel, selector);
                    clientChannel.register(selector, SelectionKey.OP_READ, handler);
                    System.out.println("[+] New client: " + clientChannel.getRemoteAddress());
                    handler.sendList();
                }

                else if (key.isReadable()) {
                    ClientHandler handler = (ClientHandler) key.attachment();
                    try {
                        handler.read();
                    }
                    catch (IOException e) {
                        System.out.println("[-] Client disconnected");
                        handler.close();
                        key.cancel();
                    }
                }

                else if (key.isWritable()) {
                    ClientHandler handler = (ClientHandler) key.attachment();
                    handler.handleWrite();
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new Server(8888).start();
    }
}