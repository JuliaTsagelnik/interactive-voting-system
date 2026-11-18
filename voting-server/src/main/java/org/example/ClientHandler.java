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

public class ClientHandler implements Runnable {
    private record Request(String action, Integer pollId, Integer choiceId, String clientId) {}

    private record StatusResponse(String type, String message) {}

    private final Socket socket;
    private final Connection db;
    private final Gson gson = new Gson();

    private ServerMain.ClientSession session;

    public ClientHandler(Socket socket, Connection db) {
        this.socket = socket;
        this.db = db;
    }

    @Override
    public void run() {
        String name = Thread.currentThread().getName();

        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {

            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            session = new ServerMain.ClientSession(out);
            ServerMain.addSession(session);
            System.out.println("[" + name + "] Клиент подключен: " + s.getRemoteSocketAddress());

            String message;
            while ((message = in.readLine()) != null) {
                System.out.println("[" + name + "] Получено: " + message);
                handleMessage(message);
            }
        } catch (IOException e) {
            System.err.println("[" + name + "] Ошибка обработки клиента: " + e.getMessage());
        } finally {
            if (session != null) {
                ServerMain.removeSession(session);
            }
            System.out.println("[" + name + "] Клиент отключен");
        }
    }

    private void handleMessage(String message) {
        Request request;
        try {
            request = gson.fromJson(message, Request.class);
        } catch (JsonSyntaxException e) {
            sendStatus("error", "Некорректный JSON");
            return;
        }
        if (request == null || request.action() == null) {
            sendStatus("error", "Не указано поле action");
            return;
        }

        switch (request.action()) {
            case "get_polls" -> handleGetPolls();
            case "get_results" -> handleGetResults(request);
            case "vote" -> handleVote(request);
            default -> sendStatus("error", "Неизвестное действие: " + request.action());
        }
    }

    private void handleGetPolls() {
        // Клиент вернулся к списку, значит ни один опрос он больше не смотрит
        session.setViewingPollId(null);
        session.send(gson.toJson(new ServerMain.PollsResponse("polls", ServerMain.listPolls())));
    }

    private void handleGetResults(Request request) {
        if (request.pollId() == null) {
            sendStatus("error", "Не указан pollId");
            return;
        }
        ServerMain.ResultsResponse results = ServerMain.buildResults(request.pollId());
        if (results == null) {
            sendStatus("error", "Опрос не найден: " + request.pollId());
            return;
        }
        // С этого момента клиент получает рассылки по этому опросу
        session.setViewingPollId(request.pollId());
        session.send(gson.toJson(results));
    }

    private void handleVote(Request request) {
        if (request.pollId() == null || request.choiceId() == null) {
            sendStatus("error", "Не указаны pollId или choiceId");
            return;
        }
        if (request.clientId() == null || request.clientId().isBlank()) {
            sendStatus("error", "Не указан clientId");
            return;
        }
        int pollId = request.pollId();
        int choiceId = request.choiceId();

        if (!ServerMain.hasOption(pollId, choiceId)) {
            sendStatus("error", "В опросе " + pollId + " нет варианта " + choiceId);
            return;
        }

        boolean accepted;
        try {
            accepted = saveVote(request.clientId(), pollId, choiceId);
        } catch (SQLException e) {
            System.err.println("Ошибка записи голоса в БД: " + e.getMessage());
            sendStatus("error", "Ошибка сервера при сохранении голоса");
            return;
        }

        if (!accepted) {
            System.out.println("Клиент " + request.clientId() + " уже голосовал в опросе " + pollId);
            sendStatus("error", "Вы уже голосовали в этом опросе");
            return;
        }

        ServerMain.registerVote(pollId, choiceId);
        sendStatus("vote_ok", "Голос принят");
        ServerMain.broadcastResults(pollId);
    }

    /**
     * Регистрирует избирателя и увеличивает счетчик в одной транзакции.
     * INSERT OR IGNORE вернет 0 строк, если пара (client_id, poll_id) уже есть.
     * Соединение SQLite общее, поэтому доступ к нему сериализован.
     */
    private boolean saveVote(String clientId, int pollId, int choiceId) throws SQLException {
        synchronized (db) {
            boolean previousAutoCommit = db.getAutoCommit();
            db.setAutoCommit(false);
            try {
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT OR IGNORE INTO voters (client_id, poll_id) VALUES (?, ?)")) {
                    insert.setString(1, clientId);
                    insert.setInt(2, pollId);
                    if (insert.executeUpdate() == 0) {
                        db.rollback();
                        return false;
                    }
                }

                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE options SET votes = votes + 1 WHERE id = ? AND poll_id = ?")) {
                    update.setInt(1, choiceId);
                    update.setInt(2, pollId);
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

    private void sendStatus(String type, String message) {
        session.send(gson.toJson(new StatusResponse(type, message)));
    }
}