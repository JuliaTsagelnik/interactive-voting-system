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
    private static final String ROLE_HOST = "host";
    private static final String ROLE_CLIENT = "client";
    private static final String HOST_NAME = "Ведущий";

    private record Request(String action, String role, String clientId, String displayName,
                           Integer pollId, Integer questionIndex,
                           Integer questionId, Integer optionId) {}

    private record StatusResponse(String type, String code, String message, Integer questionId) {}

    private enum VoteResult { ACCEPTED, ALREADY_VOTED, REJECTED, FAILED }

    private final Socket socket;
    private final Connection db;
    private final Gson gson = new Gson();

    private ServerMain.ClientSession session;
    // Причина отказа, если saveVote вернул REJECTED (поле используется только потоком этого клиента)
    private ServerMain.VoteGate rejectedGate;

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
            String who = session != null && session.displayName() != null ? session.displayName() : "?";
            System.out.println("[" + name + "] Клиент отключен (" + who + ")");
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
            case "host_finish_poll" -> handleFinishPoll();
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

        String displayName = ROLE_HOST.equals(role)
                ? HOST_NAME
                : ServerMain.registerName(clientId, request.displayName());
        session.setIdentity(role, clientId, displayName);

        if (ROLE_HOST.equals(role)) {
            session.send(ServerMain.catalogJson());
        }
        // Новому клиенту приходит waiting, active_question или poll_summary, если опрос уже завершён
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

    private void handleFinishPoll() {
        if (!requireHost()) {
            return;
        }
        try {
            switch (ServerMain.finishPoll()) {
                case OK -> { }
                case NO_ACTIVE_POLL -> sendStatus("error", "no_active_poll", "Нет открытого опроса", null);
                case ALREADY_FINISHED -> sendStatus("error", "already_finished", "Опрос уже завершён", null);
            }
        } catch (SQLException e) {
            System.err.println("Ошибка формирования итогов: " + e.getMessage());
            sendStatus("error", "db_error", "Не удалось сформировать итоги опроса", null);
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

        ServerMain.VoteGate gate = ServerMain.voteGate(questionId);
        if (gate != ServerMain.VoteGate.OPEN) {
            sendGateError(gate, questionId);
            return;
        }
        if (!ServerMain.hasOption(questionId, optionId)) {
            sendStatus("error", "bad_option", "В вопросе " + questionId + " нет варианта " + optionId, questionId);
            return;
        }

        VoteResult result;
        try {
            result = saveVote(clientId, questionId, optionId);
        } catch (SQLException e) {
            System.err.println("Ошибка записи голоса в БД: " + e.getMessage());
            sendStatus("error", "db_error", "Ошибка сервера при сохранении голоса", questionId);
            return;
        }

        switch (result) {
            case ACCEPTED -> {
                sendStatus("vote_ok", null, "Ваш голос учтён", questionId);
                ServerMain.voteAccepted(questionId, optionId);
            }
            case ALREADY_VOTED -> {
                System.out.println("Клиент " + clientId + " уже голосовал в вопросе " + questionId);
                sendStatus("error", "already_voted", "Вы уже голосовали в этом вопросе", questionId);
            }
            case REJECTED -> sendGateError(rejectedGate, questionId);
            case FAILED -> sendStatus("error", "bad_option", "Не удалось сохранить голос", questionId);
        }
    }

    private void sendGateError(ServerMain.VoteGate gate, int questionId) {
        switch (gate) {
            case NO_POLL -> sendStatus("error", "no_active_poll", "Сейчас нет открытого опроса", questionId);
            case FINISHED -> sendStatus("error", "poll_finished",
                    "Опрос завершён, приём голосов закрыт", questionId);
            case WRONG_QUESTION -> sendStatus("error", "question_not_active",
                    "Этот вопрос сейчас не активен", questionId);
            case CLOSED -> sendStatus("error", "question_closed",
                    "Время вышло, приём ответов на этот вопрос закрыт", questionId);
            case OPEN -> sendStatus("error", "question_not_active", "Не удалось принять голос", questionId);
        }
    }

    /**
     * Регистрирует избирателя вместе с выбранным вариантом и увеличивает счётчик в одной транзакции.
     * Допустимость голоса перепроверяется под замком БД: finishPoll и раскрытие ответа ставят свои метки
     * под тем же замком, поэтому после закрытия вопроса новые голоса в базу не попадут.
     * INSERT OR IGNORE вернёт 0 строк, если пара (client_id, question_id) уже есть.
     */
    private VoteResult saveVote(String clientId, int questionId, int optionId) throws SQLException {
        synchronized (db) {
            ServerMain.VoteGate gate = ServerMain.voteGate(questionId);
            if (gate != ServerMain.VoteGate.OPEN) {
                rejectedGate = gate;
                return VoteResult.REJECTED;
            }

            boolean previousAutoCommit = db.getAutoCommit();
            db.setAutoCommit(false);
            try {
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT OR IGNORE INTO voters (client_id, question_id, option_id) VALUES (?, ?, ?)")) {
                    insert.setString(1, clientId);
                    insert.setInt(2, questionId);
                    insert.setInt(3, optionId);
                    if (insert.executeUpdate() == 0) {
                        db.rollback();
                        return VoteResult.ALREADY_VOTED;
                    }
                }

                try (PreparedStatement update = db.prepareStatement(
                        "UPDATE options SET votes = votes + 1 WHERE id = ? AND question_id = ?")) {
                    update.setInt(1, optionId);
                    update.setInt(2, questionId);
                    if (update.executeUpdate() != 1) {
                        db.rollback();
                        return VoteResult.FAILED;
                    }
                }

                db.commit();
                return VoteResult.ACCEPTED;
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