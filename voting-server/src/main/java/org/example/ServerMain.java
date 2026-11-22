package org.example;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:voting.db";
    private static final String POLLS_RESOURCE = "/polls.json";

    // ---------- Формат polls.json ----------

    public record SeedQuestion(String text, List<String> options) {}
    public record SeedPoll(String title, List<SeedQuestion> questions) {}
    public record SeedTheme(String theme, List<SeedPoll> polls) {}

    // ---------- Форматы ответов сервера ----------

    public record CatalogPoll(int id, String title, int questionCount) {}
    public record CatalogTheme(int id, String title, List<CatalogPoll> polls) {}
    public record CatalogResponse(String type, List<CatalogTheme> themes) {}
    public record WaitingResponse(String type) {}
    public record OptionResult(int id, String text, int votes) {}
    public record ActiveQuestionResponse(String type, int pollId, String theme, String pollTitle,
                                         int questionId, int questionIndex, int totalQuestions,
                                         String text, List<OptionResult> options) {}

    // ---------- Данные в памяти (структура после загрузки не меняется, меняются только голоса) ----------

    private record OptionData(int id, String text) {}
    private record QuestionData(int id, int pollId, int orderNum, String text, List<OptionData> options) {}
    private record PollData(int id, int themeId, String title, List<QuestionData> questions) {}
    private record ThemeData(int id, String title, List<PollData> polls) {}

    private record ActiveState(int pollId, int questionIndex) {}

    private static final List<ThemeData> themes = new ArrayList<>();
    private static final Map<Integer, String> themeTitles = new HashMap<>();
    private static final Map<Integer, PollData> pollsById = new HashMap<>();
    private static final Map<Integer, QuestionData> questionsById = new HashMap<>();
    // optionId -> число голосов
    private static final Map<Integer, Integer> optionVotes = new ConcurrentHashMap<>();

    // Активный опрос и номер вопроса. null, пока ведущий ничего не открыл.
    private static volatile ActiveState active;

    /** Подключение клиента: поток вывода, роль и идентификатор, присланные в hello. */
    public static final class ClientSession {
        private final PrintWriter out;
        private volatile String role;
        private volatile String clientId;

        public ClientSession(PrintWriter out) {
            this.out = out;
        }

        public void send(String json) {
            out.println(json);
        }

        public boolean hasError() {
            return out.checkError();
        }

        public String role() {
            return role;
        }

        public String clientId() {
            return clientId;
        }

        public void setIdentity(String role, String clientId) {
            this.role = role;
            this.clientId = clientId;
        }
    }

    private static final CopyOnWriteArrayList<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        ExecutorService pool = Executors.newCachedThreadPool();

        try (Connection db = DriverManager.getConnection(DB_URL);
             ServerSocket serverSocket = new ServerSocket(PORT)) {

            initDatabase(db);
            loadData(db);
            System.out.println("База подключена. Тем: " + themes.size() + ", опросов: " + pollsById.size()
                    + ", вопросов: " + questionsById.size());

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

    private static void initDatabase(Connection db) throws SQLException, IOException {
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }

        dropLegacyTables(db);

        try (Statement st = db.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS themes (
                        id    INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS polls (
                        id       INTEGER PRIMARY KEY AUTOINCREMENT,
                        theme_id INTEGER NOT NULL,
                        title    TEXT NOT NULL,
                        FOREIGN KEY (theme_id) REFERENCES themes(id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS questions (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        poll_id   INTEGER NOT NULL,
                        order_num INTEGER NOT NULL,
                        text      TEXT NOT NULL,
                        FOREIGN KEY (poll_id) REFERENCES polls(id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS options (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        question_id INTEGER NOT NULL,
                        text        TEXT NOT NULL,
                        votes       INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (question_id) REFERENCES questions(id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS voters (
                        client_id   TEXT NOT NULL,
                        question_id INTEGER NOT NULL,
                        PRIMARY KEY (client_id, question_id)
                    )""");
        }

        boolean empty;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM themes")) {
            empty = rs.next() && rs.getInt(1) == 0;
        }
        if (empty) {
            seedFromResource(db);
        }
    }

    /**
     * Если хотя бы одна существующая таблица не содержит нужных столбцов (старая структура),
     * удаляются все пять таблиц: данные старого формата с новой схемой несовместимы.
     */
    private static void dropLegacyTables(Connection db) throws SQLException {
        Map<String, Set<String>> expected = Map.of(
                "themes", Set.of("id", "title"),
                "polls", Set.of("id", "theme_id", "title"),
                "questions", Set.of("id", "poll_id", "order_num", "text"),
                "options", Set.of("id", "question_id", "text", "votes"),
                "voters", Set.of("client_id", "question_id"));

        boolean incompatible = false;
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            Set<String> columns = tableColumns(db, entry.getKey());
            if (!columns.isEmpty() && !columns.containsAll(entry.getValue())) {
                incompatible = true;
                break;
            }
        }

        if (incompatible) {
            try (Statement st = db.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS voters");
                st.executeUpdate("DROP TABLE IF EXISTS options");
                st.executeUpdate("DROP TABLE IF EXISTS questions");
                st.executeUpdate("DROP TABLE IF EXISTS polls");
                st.executeUpdate("DROP TABLE IF EXISTS themes");
            }
            System.out.println("Таблицы старой структуры удалены, будут созданы заново");
        }
    }

    private static Set<String> tableColumns(Connection db, String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private static void seedFromResource(Connection db) throws SQLException, IOException {
        SeedTheme[] seed;
        try (InputStream is = ServerMain.class.getResourceAsStream(POLLS_RESOURCE)) {
            if (is == null) {
                throw new IOException("Ресурс " + POLLS_RESOURCE + " не найден в classpath");
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                seed = gson.fromJson(reader, SeedTheme[].class);
            }
        }
        if (seed == null || seed.length == 0) {
            throw new IOException("Файл " + POLLS_RESOURCE + " пуст");
        }

        db.setAutoCommit(false);
        try {
            for (SeedTheme theme : seed) {
                int themeId = insertReturningId(db, "INSERT INTO themes (title) VALUES (?)", theme.theme());
                for (SeedPoll poll : theme.polls()) {
                    int pollId = insertReturningId(db,
                            "INSERT INTO polls (theme_id, title) VALUES (?, ?)", themeId, poll.title());
                    int order = 0;
                    for (SeedQuestion question : poll.questions()) {
                        int questionId = insertReturningId(db,
                                "INSERT INTO questions (poll_id, order_num, text) VALUES (?, ?, ?)",
                                pollId, order++, question.text());
                        for (String option : question.options()) {
                            insertReturningId(db,
                                    "INSERT INTO options (question_id, text, votes) VALUES (?, ?, 0)",
                                    questionId, option);
                        }
                    }
                }
            }
            db.commit();
            System.out.println("База заполнена из " + POLLS_RESOURCE);
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static int insertReturningId(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static void loadData(Connection db) throws SQLException {
        Map<Integer, List<OptionData>> optionsByQuestion = new HashMap<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, question_id, text, votes FROM options ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                optionsByQuestion.computeIfAbsent(rs.getInt("question_id"), k -> new ArrayList<>())
                        .add(new OptionData(id, rs.getString("text")));
                optionVotes.put(id, rs.getInt("votes"));
            }
        }

        Map<Integer, List<QuestionData>> questionsByPoll = new HashMap<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, poll_id, order_num, text FROM questions ORDER BY poll_id, order_num")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int pollId = rs.getInt("poll_id");
                QuestionData question = new QuestionData(id, pollId, rs.getInt("order_num"),
                        rs.getString("text"), List.copyOf(optionsByQuestion.getOrDefault(id, List.of())));
                questionsByPoll.computeIfAbsent(pollId, k -> new ArrayList<>()).add(question);
                questionsById.put(id, question);
            }
        }

        Map<Integer, List<PollData>> pollsByTheme = new HashMap<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, theme_id, title FROM polls ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int themeId = rs.getInt("theme_id");
                PollData poll = new PollData(id, themeId, rs.getString("title"),
                        List.copyOf(questionsByPoll.getOrDefault(id, List.of())));
                pollsByTheme.computeIfAbsent(themeId, k -> new ArrayList<>()).add(poll);
                pollsById.put(id, poll);
            }
        }

        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, title FROM themes ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                themes.add(new ThemeData(id, title, List.copyOf(pollsByTheme.getOrDefault(id, List.of()))));
                themeTitles.put(id, title);
            }
        }
    }

    // ---------- Каталог и состояние ----------

    public static CatalogResponse buildCatalog() {
        List<CatalogTheme> result = new ArrayList<>();
        for (ThemeData theme : themes) {
            List<CatalogPoll> polls = new ArrayList<>();
            for (PollData poll : theme.polls()) {
                polls.add(new CatalogPoll(poll.id(), poll.title(), poll.questions().size()));
            }
            result.add(new CatalogTheme(theme.id(), theme.title(), polls));
        }
        return new CatalogResponse("catalog", result);
    }

    public static String catalogJson() {
        return gson.toJson(buildCatalog());
    }

    /** JSON текущего состояния: active_question, если опрос открыт, иначе waiting. */
    public static String currentStateJson() {
        ActiveState state = active;
        if (state == null) {
            return gson.toJson(new WaitingResponse("waiting"));
        }
        PollData poll = pollsById.get(state.pollId());
        QuestionData question = poll.questions().get(state.questionIndex());

        List<OptionResult> options = new ArrayList<>();
        for (OptionData option : question.options()) {
            options.add(new OptionResult(option.id(), option.text(), optionVotes.getOrDefault(option.id(), 0)));
        }
        return gson.toJson(new ActiveQuestionResponse("active_question", poll.id(),
                themeTitles.get(poll.themeId()), poll.title(), question.id(), state.questionIndex(),
                poll.questions().size(), question.text(), options));
    }

    /** Id текущего вопроса или null, если опрос не открыт. */
    public static Integer activeQuestionId() {
        ActiveState state = active;
        if (state == null) {
            return null;
        }
        return pollsById.get(state.pollId()).questions().get(state.questionIndex()).id();
    }

    public static boolean hasOption(int questionId, int optionId) {
        QuestionData question = questionsById.get(questionId);
        if (question == null) {
            return false;
        }
        for (OptionData option : question.options()) {
            if (option.id() == optionId) {
                return true;
            }
        }
        return false;
    }

    // ---------- Управление опросом (только из-под ведущего) ----------

    public static synchronized boolean openPoll(int pollId) {
        PollData poll = pollsById.get(pollId);
        if (poll == null || poll.questions().isEmpty()) {
            return false;
        }
        active = new ActiveState(pollId, 0);
        broadcastState();
        return true;
    }

    public static synchronized boolean setQuestion(int questionIndex) {
        ActiveState state = active;
        if (state == null) {
            return false;
        }
        int total = pollsById.get(state.pollId()).questions().size();
        if (questionIndex < 0 || questionIndex >= total) {
            return false;
        }
        active = new ActiveState(state.pollId(), questionIndex);
        broadcastState();
        return true;
    }

    public static synchronized void closePoll() {
        active = null;
        broadcastState();
    }

    /** Учитывает принятый (уже записанный в БД) голос в памяти и рассылает новое состояние. */
    public static synchronized void voteAccepted(int optionId) {
        optionVotes.merge(optionId, 1, Integer::sum);
        broadcastState();
    }

    // ---------- Клиенты ----------

    public static void addSession(ClientSession session) {
        sessions.add(session);
    }

    public static void removeSession(ClientSession session) {
        sessions.remove(session);
    }

    /**
     * Рассылает текущее состояние всем, кто прошёл hello (ведущему и участникам).
     * Синхронизация гарантирует, что старый снимок не придёт позже нового.
     */
    public static synchronized void broadcastState() {
        String json = currentStateJson();
        int delivered = 0;
        for (ClientSession session : sessions) {
            if (session.role() == null) {
                continue;
            }
            session.send(json);
            if (session.hasError()) {
                sessions.remove(session);
            } else {
                delivered++;
            }
        }
        System.out.println("Рассылка (получателей: " + delivered + "): " + json);
    }
}