package org.example;
// ClientHandler.java
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientHandler implements Runnable {
    private record VoteRequest(String action, String choice) {}

    private final Socket socket;
    private final ConcurrentHashMap<String, Integer> votes;
    private final CopyOnWriteArrayList<PrintWriter> clients;
    private final Gson gson = new Gson();

    public ClientHandler(Socket socket,
                         ConcurrentHashMap<String, Integer> votes,
                         CopyOnWriteArrayList<PrintWriter> clients) {
        this.socket = socket;
        this.votes = votes;
        this.clients = clients;
    }

    @Override
    public void run() {
        PrintWriter out = null;
        String name = Thread.currentThread().getName();

        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {

            out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            System.out.println("[" + name + "] Клиент подключен: " + s.getRemoteSocketAddress());

            clients.add(out);
            ServerMain.broadcastResults();

            String message;
            while ((message = in.readLine()) != null) {
                System.out.println("[" + name + "] Получено: " + message);
                handleMessage(message);
            }
        } catch (IOException e) {
            System.err.println("[" + name + "] Ошибка обработки клиента: " + e.getMessage());
        } finally {
            if (out != null) {
                clients.remove(out);
            }
            System.out.println("[" + name + "] Клиент отключен");
        }
    }

    private void handleMessage(String message) {
        VoteRequest request;
        try {
            request = gson.fromJson(message, VoteRequest.class);
        } catch (JsonSyntaxException e) {
            System.err.println("Некорректный JSON: " + e.getMessage());
            return;
        }

        if (request == null || !"vote".equals(request.action())) {
            System.err.println("Неизвестное действие, сообщение пропущено");
            return;
        }
        if (request.choice() == null || !votes.containsKey(request.choice())) {
            System.err.println("Неизвестный вариант: " + request.choice());
            return;
        }

        votes.merge(request.choice(), 1, Integer::sum);
        ServerMain.broadcastResults();
    }
}