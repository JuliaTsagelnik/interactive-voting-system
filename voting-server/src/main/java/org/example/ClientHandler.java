// ClientHandler.java
package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientHandler implements Runnable {
    private record VoteRequest(String action, String choice, String clientId) {}

    private final Socket socket;
    private final ConcurrentHashMap<String, Integer> votes;
    private final CopyOnWriteArrayList<PrintWriter> clients;
    private final Connection db;
    private final Gson gson = new Gson();

    public ClientHandler(Socket socket,
                         ConcurrentHashMap<String, Integer> votes,
                         CopyOnWriteArrayList<PrintWriter> clients,
                         Connection db) {
        this.socket = socket;
        this.votes = votes;
        this.clients = clients;
        this.db = db;
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
        if (request.clientId() == null || request.clientId().isBlank()) {
            System.err.println("Не указан clientId, голос отклонён");
            return;
        }

        boolean accepted;
        try {
            accepted = saveVote(request.clientId(), request.choice());
        } catch (SQLException e) {
            System.err.println("Ошибка записи голоса в БД: " + e.getMessage());
            return;
        }

        if (!accepted) {
            System.out.println("Клиент " + request.clientId() + " уже голосовал, голос проигнорирован");
            return;
        }

        votes.merge(request.choice(), 1, Integer::sum);
        ServerMain.broadcastResults();
    }

    /**
     * Регистрирует избирателя и увеличивает счётчик в одной транзакции.
     * INSERT OR IGNORE возвращает 0 изменённых строк, если clientId уже есть
     * в таблице, так что проверка и вставка выполняются одним запросом.
     * Соединение SQLite общее, поэтому доступ к нему сериализован.
     */
    private boolean saveVote(String clientId, String choice) throws SQLException {
        synchronized (db) {
            boolean previousAutoCommit = db.getAutoCommit();
            db.setAutoCommit(false);
            try {
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT OR IGNORE INTO voters (client_id) VALUES (?)")) {
                    insert.setString(1, clientId);
                    if (insert.executeUpdate() == 0) {
                        db.rollback();
                        return false;
                    }
                }

                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE options SET votes = votes + 1 WHERE name = ?")) {
                    update.setString(1, choice);
                    if (update.executeUpdate() != 1) {
                        db.rollback();
                        return false;
                    }
                }

                db.commit();
                return true;
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(previousAutoCommit);
            }
        }
    }
}