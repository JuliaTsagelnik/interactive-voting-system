package org.example;
// ServerMain.java
// Зависимость: com.google.code.gson:gson:2.10.1 (или новее)
import com.google.gson.Gson;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
    private static final int PORT = 8080;

    public static final String OPTION_A = "Вариант А";
    public static final String OPTION_B = "Вариант Б";
    public static final String OPTION_C = "Вариант В";

    private static final List<String> OPTIONS = List.of(OPTION_A, OPTION_B, OPTION_C);

    private static final ConcurrentHashMap<String, Integer> votes = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<PrintWriter> clients = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        OPTIONS.forEach(option -> votes.put(option, 0));

        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер запущен, порт " + PORT + ". Ожидание подключений...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, votes, clients));
            }
        } catch (IOException e) {
            System.err.println("Ошибка сервера: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Формирует снимок результатов в фиксированном порядке вариантов
     * и рассылает его всем клиентам из списка. Синхронизация гарантирует,
     * что более старый снимок не будет отправлен позже более нового.
     */
    public static synchronized void broadcastResults() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (String option : OPTIONS) {
            snapshot.put(option, votes.get(option));
        }
        String json = gson.toJson(Map.of("results", snapshot));

        for (PrintWriter writer : clients) {
            writer.println(json);
            if (writer.checkError()) {
                clients.remove(writer);
            }
        }
        System.out.println("Рассылка: " + json);
    }
}