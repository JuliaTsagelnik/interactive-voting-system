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
    private static final String ROLE_HOST = "host";
    private static final String ROLE_CLIENT = "client";

    private record Request(String action, String role, String clientId,
                           Integer pollId, Integer questionIndex,
                           Integer questionId, Integer optionId) {}

    private record StatusResponse(String type, String code, String message, Integer questionId) {}

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
            System.out.println("[" + name + "] Клиент отключен (" + (session != null ? session.role() : "?") + ")");
        }
    }

    private void handleMessage(String message) {
        Request request;
        try {
            request = gson.fromJson(message, Request.class);
        } catch (JsonSyntaxException e) {
            sendStatus("error", "bad_json", "Некорректный JSON", null);
            return;
        }
        if (request == null || request.action() == null) {
            sendStatus("error", "no_action", "Не указано поле action", null);
            return;
        }

        if ("hello".equals(request.action())) {
            handleHello(request);
            return;
        }
        if (session.role() == null) {
            sendStatus("error", "no_hello", "Сначала отправьте hello с ролью", null);
            return;
        }

        switch (request.action()) {
            case "host_open_poll" -> handleOpenPoll(request);
            case "host_set_question" -> handleSetQuestion(request);
            case "host_close_poll" -> handleClosePoll();
            case "vote" -> handleVote(request);
            default -> sendStatus("error", "unknown_action", "Неизвестное действие: " + request.action(), null);
        }
    }

    // ---------- hello ----------

    private void handleHello(Request request) {
        String role = request.role();
        if (!ROLE_HOST.equals(role) && !ROLE_CLIENT.equals(role)) {
            sendStatus("error", "bad_role", "Роль должна быть host или client", null);
            return;
        }
        String clientId = request.clientId();
        if (ROLE_CLIENT.equals(role) && (clientId == null || clientId.isBlank())) {
            sendStatus("error", "no_client_id", "Не указан clientId", null);
            return;
        }

        session.setIdentity(role, clientId);

        if (ROLE_HOST.equals(role)) {
            session.send(ServerMain.catalogJson());
        }
        session.send(ServerMain.currentStateJson());
    }

    // ---------- Действия ведущего ----------

    private boolean requireHost() {
        if (!ROLE_HOST.equals(session.role())) {
            sendStatus("error", "forbidden", "Действие доступно только ведущему", null);
            return false;
        }
        return true;
    }

    private void handleOpenPoll(Request request) {
        if (!requireHost()) {
            return;
        }
        if (request.pollId() == null) {
            sendStatus("error", "bad_request", "Не указан pollId", null);
            return;
        }
        if (!ServerMain.openPoll(request.pollId())) {
            sendStatus("error", "poll_not_found", "Опрос не найден или в нём нет вопросов: " + request.pollId(), null);
        }
    }

    private void handleSetQuestion(Request request) {
        if (!requireHost()) {
            return;
        }
        if (request.questionIndex() == null) {
            sendStatus("error", "bad_request", "Не указан questionIndex", null);
            return;
        }
        if (!ServerMain.setQuestion(request.questionIndex())) {
            sendStatus("error", "bad_question_index",
                    "Нет открытого опроса или вопроса с номером " + request.questionIndex(), null);
        }
    }

    private void handleClosePoll() {
        if (!requireHost()) {
            return;
        }
        ServerMain.closePoll();
    }

    // ---------- Голосование ----------

    private void handleVote(Request request) {
        if (!ROLE_CLIENT.equals(session.role())) {
            sendStatus("error", "forbidden", "Голосовать могут только участники", null);
            return;
        }
        if (request.questionId() == null || request.optionId() == null) {
            sendStatus("error", "bad_request", "Не указаны questionId или optionId", null);
            return;
        }

        String clientId = (request.clientId() != null && !request.clientId().isBlank())
                ? request.clientId()
                : session.clientId();
        int questionId = request.questionId();
        int optionId = request.optionId();

        Integer activeId = ServerMain.activeQuestionId();
        if (activeId == null) {
            sendStatus("error", "no_active_poll", "Сейчас нет открытого опроса", questionId);
            return;
        }
        if (activeId != questionId) {
            sendStatus("error", "question_not_active", "Этот вопрос сейчас не активен", questionId);
            return;
        }
        if (!ServerMain.hasOption(questionId, optionId)) {
            sendStatus("error", "bad_option", "В вопросе " + questionId + " нет варианта " + optionId, questionId);
            return;
        }

        boolean accepted;
        try {
            accepted = saveVote(clientId, questionId, optionId);
        } catch (SQLException e) {
            System.err.println("Ошибка записи голоса в БД: " + e.getMessage());
            sendStatus("error", "db_error", "Ошибка сервера при сохранении голоса", questionId);
            return;
        }

        if (!accepted) {
            System.out.println("Клиент " + clientId + " уже голосовал в вопросе " + questionId);
            sendStatus("error", "already_voted", "Вы уже голосовали в этом вопросе", questionId);
            return;
        }

        sendStatus("vote_ok", null, "Ваш голос учтён", questionId);
        ServerMain.voteAccepted(optionId);
    }

    /**
     * Регистрирует избирателя и увеличивает счётчик варианта в одной транзакции.
     * INSERT OR IGNORE вернёт 0 строк, если пара (client_id, question_id) уже есть.
     * Соединение SQLite общее, поэтому доступ к нему сериализован.
     */
    private boolean saveVote(String clientId, int questionId, int optionId) throws SQLException {
        synchronized (db) {
            boolean previousAutoCommit = db.getAutoCommit();
            db.setAutoCommit(false);
            try {
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT OR IGNORE INTO voters (client_id, question_id) VALUES (?, ?)")) {
                    insert.setString(1, clientId);
                    insert.setInt(2, questionId);
                    if (insert.executeUpdate() == 0) {
                        db.rollback();
                        return false;
                    }
                }

                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE options SET votes = votes + 1 WHERE id = ? AND question_id = ?")) {
                    update.setInt(1, optionId);
                    update.setInt(2, questionId);
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

    private void sendStatus(String type, String code, String message, Integer questionId) {
        session.send(gson.toJson(new StatusResponse(type, code, message, questionId)));
    }
}