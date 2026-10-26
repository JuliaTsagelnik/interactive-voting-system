package org.example;
// ServerMain.java
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
    private static final int PORT = 8080;

    public static final String OPTION_A = "Вариант А";
    public static final String OPTION_B = "Вариант Б";
    public static final String OPTION_C = "Вариант В";

    private static final ConcurrentHashMap<String, Integer> votes = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        votes.put(OPTION_A, 0);
        votes.put(OPTION_B, 0);
        votes.put(OPTION_C, 0);

        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер запущен, порт " + PORT + ". Ожидание подключений...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, votes));
            }
        } catch (IOException e) {
            System.err.println("Ошибка сервера: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }
}