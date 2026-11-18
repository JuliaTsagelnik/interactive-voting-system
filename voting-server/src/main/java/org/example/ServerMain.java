// ServerMain.java
package org.example;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:voting.db";

    // Форматы ответов сервера. Поле type позволяет клиенту отличать сообщения друг от друга.
    public record PollInfo(int id, String question) {}
    public record OptionResult(int id, String name, int votes) {}
    public record PollsResponse(String type, List<PollInfo> polls) {}
    public record ResultsResponse(String type, int pollId, String question, List<OptionResult> options) {}

    /** Подключение клиента и опрос, который он сейчас смотрит (null, если никакой). */
    public static final class ClientSession {
        private final PrintWriter out;
        private volatile Integer viewingPollId;

        public ClientSession(PrintWriter out) {
            this.out = out;
        }

        public void send(String json) {
            out.println(json);
        }

        public boolean hasError() {
            return out.checkError();
        }

        public Integer viewingPollId() {
            return viewingPollId;
        }

        public void setViewingPollId(Integer pollId) {
            this.viewingPollId = pollId;
        }
    }

    // pollId -> вопрос (отсортировано по id)
    private static final Map<Integer, String> questions = new ConcurrentSkipListMap<>();
    // pollId -> (optionId -> название). Заполняется до старта приема подключений и дальше не меняется.
    private static final Map<Integer, Map<Integer, String>> optionNames = new ConcurrentHashMap<>();
    // pollId -> (optionId -> число голосов)
    private static final Map<Integer, Map<Integer, Integer>> votes = new ConcurrentHashMap<>();

    private static final CopyOnWriteArrayList<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        ExecutorService pool = Executors.newCachedThreadPool();

        try (Connection db = DriverManager.getConnection(DB_URL);
             ServerSocket serverSocket = new ServerSocket(PORT)) {

            initDatabase(db);
            loadData(db);
            System.out.println("База подключена. Опросов загружено: " + questions.size());

            System.out.println("Сервер запущен, порт " + PORT + ". Ожидание подключений...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, db));
            }
        } catch (SQLException e) {
            System.err.println("Ошибка базы данных: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Ошибка сервера: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    // ---------- База данных ----------

    private static void initDatabase(Connection db) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }

        dropLegacyTables(db);

        try (Statement st = db.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS polls (
                        id       INTEGER PRIMARY KEY AUTOINCREMENT,
                        question TEXT NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS options (
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        poll_id INTEGER NOT NULL,
                        name    TEXT NOT NULL,
                        votes   INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (poll_id) REFERENCES polls(id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS voters (
                        client_id TEXT NOT NULL,
                        poll_id   INTEGER NOT NULL,
                        PRIMARY KEY (client_id, poll_id)
                    )""");
        }

        boolean empty;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM polls")) {
            empty = rs.next() && rs.getInt(1) == 0;
        }
        if (empty) {
            seedPoll(db, "Любимый цвет?", List.of("Красный", "Синий", "Зелёный"));
            seedPoll(db, "Любимый напиток?", List.of("Чай", "Кофе", "Сок", "Вода"));
        }
    }

    /** Старые options/voters из прошлой версии не содержат poll_id, CREATE IF NOT EXISTS их бы не заменил. */
    private static void dropLegacyTables(Connection db) throws SQLException {
        boolean exists = false;
        boolean hasPollId = false;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(options)")) {
            while (rs.next()) {
                exists = true;
                if ("poll_id".equals(rs.getString("name"))) {
                    hasPollId = true;
                }
            }
        }
        if (exists && !hasPollId) {
            try (Statement st = db.createStatement()) {
                st.executeUpdate("DROP TABLE options");
                st.executeUpdate("DROP TABLE IF EXISTS voters");
            }
            System.out.println("Таблицы старой структуры удалены, будут созданы заново");
        }
    }

    private static void seedPoll(Connection db, String question, List<String> options) throws SQLException {
        int pollId;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO polls (question) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, question);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                pollId = keys.getInt(1);
            }
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO options (poll_id, name, votes) VALUES (?, ?, 0)")) {
            for (String option : options) {
                ps.setInt(1, pollId);
                ps.setString(2, option);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void loadData(Connection db) throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, question FROM polls ORDER BY id")) {
            while (rs.next()) {
                questions.put(rs.getInt("id"), rs.getString("question"));
            }
        }
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, poll_id, name, votes FROM options ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int pollId = rs.getInt("poll_id");
                optionNames.computeIfAbsent(pollId, k -> new TreeMap<>()).put(id, rs.getString("name"));
                votes.computeIfAbsent(pollId, k -> new ConcurrentHashMap<>()).put(id, rs.getInt("votes"));
            }
        }
    }

    // ---------- Доступ к данным в памяти ----------

    public static boolean hasOption(int pollId, int optionId) {
        Map<Integer, String> names = optionNames.get(pollId);
        return names != null && names.containsKey(optionId);
    }

    public static List<PollInfo> listPolls() {
        List<PollInfo> result = new ArrayList<>();
        questions.forEach((id, question) -> result.add(new PollInfo(id, question)));
        return result;
    }

    /** Снимок результатов опроса или null, если такого опроса нет. */
    public static ResultsResponse buildResults(int pollId) {
        String question = questions.get(pollId);
        Map<Integer, String> names = optionNames.get(pollId);
        Map<Integer, Integer> counts = votes.get(pollId);
        if (question == null || names == null || counts == null) {
            return null;
        }
        List<OptionResult> options = new ArrayList<>();
        names.forEach((id, name) -> options.add(new OptionResult(id, name, counts.getOrDefault(id, 0))));
        return new ResultsResponse("results", pollId, question, options);
    }

    public static void registerVote(int pollId, int optionId) {
        votes.get(pollId).merge(optionId, 1, Integer::sum);
    }

    // ---------- Клиенты ----------

    public static void addSession(ClientSession session) {
        sessions.add(session);
    }

    public static void removeSession(ClientSession session) {
        sessions.remove(session);
    }

    /**
     * Рассылает свежие результаты опроса только тем клиентам, которые его сейчас смотрят.
     * Синхронизация гарантирует, что старый снимок не придет позже нового.
     */
    public static synchronized void broadcastResults(int pollId) {
        ResultsResponse snapshot = buildResults(pollId);
        if (snapshot == null) {
            return;
        }
        String json = gson.toJson(snapshot);

        int delivered = 0;
        for (ClientSession session : sessions) {
            Integer viewing = session.viewingPollId();
            if (viewing != null && viewing == pollId) {
                session.send(json);
                if (session.hasError()) {
                    sessions.remove(session);
                } else {
                    delivered++;
                }
            }
        }
        System.out.println("Рассылка по опросу " + pollId + " (получателей: " + delivered + "): " + json);
    }
}