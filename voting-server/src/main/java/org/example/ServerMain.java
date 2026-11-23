// ServerMain.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerMain {
    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:voting.db";
    private static final String POLLS_RESOURCE = "/polls.json";
    private static final int MAX_NAME_LENGTH = 40;
    private static final int DEFAULT_TIME_LIMIT = 10;
    private static final String MODE_POLL = "poll";
    private static final String MODE_QUIZ = "quiz";

    // ---------- Формат polls.json ----------

    /** correctIndex нужен только для викторин (0, 1 или 2). */
    public record SeedQuestion(String text, List<String> options, Integer correctIndex) {}
    /** profiles, mode и timeLimitSeconds необязательны. */
    public record SeedPoll(String title, List<SeedQuestion> questions, Map<String, String> profiles,
                           String mode, Integer timeLimitSeconds) {}
    public record SeedTheme(String theme, List<SeedPoll> polls) {}

    // ---------- Форматы ответов сервера ----------

    public record CatalogPoll(int id, String title, int questionCount, String mode) {}
    public record CatalogTheme(int id, String title, List<CatalogPoll> polls) {}
    public record CatalogResponse(String type, List<CatalogTheme> themes) {}
    public record WaitingResponse(String type) {}
    public record OptionResult(int id, String text, int votes) {}
    /**
     * correctOptionId заполняется только при revealed == true, до этого поле не сериализуется.
     * secondsLeft и correctOptionId для обычных опросов тоже отсутствуют.
     */
    public record ActiveQuestionResponse(String type, int pollId, String theme, String pollTitle,
                                         int questionId, int questionIndex, int totalQuestions,
                                         String text, List<OptionResult> options,
                                         String mode, boolean revealed, Integer secondsLeft,
                                         Integer correctOptionId) {}
    public record TimerTick(String type, int questionId, int secondsLeft) {}

    // Итоги опроса (orderNum и questionNumber нумеруются с единицы)
    public record SummaryOption(int id, String text, int votes) {}
    public record SummaryQuestion(int orderNum, String text, int totalVotes, List<SummaryOption> options,
                                  Integer correctOptionId, String correctOptionText) {}
    public record SummaryAnswer(int questionNumber, String questionText, String optionText,
                                Boolean correct, String correctOptionText) {}
    public record SummaryParticipant(String clientId, String displayName, int answeredCount, int totalQuestions,
                                     List<Integer> optionIndexCounts, int majorityMatchPercent,
                                     String verdict, List<SummaryAnswer> answers,
                                     Integer correctCount, Integer grade10) {}
    public record PollSummaryResponse(String type, int pollId, String theme, String pollTitle, String mode,
                                      List<SummaryQuestion> questions, List<SummaryParticipant> participants) {}

    public enum FinishResult { OK, NO_ACTIVE_POLL, ALREADY_FINISHED }

    /** Можно ли сейчас принимать голос по вопросу. */
    public enum VoteGate { OPEN, NO_POLL, FINISHED, WRONG_QUESTION, CLOSED }

    // ---------- Данные в памяти (структура после загрузки не меняется, меняются только голоса) ----------

    private record OptionData(int id, String text, boolean correct) {}

    private record QuestionData(int id, int pollId, int orderNum, String text, List<OptionData> options) {
        Integer correctOptionId() {
            for (OptionData option : options) {
                if (option.correct()) {
                    return option.id();
                }
            }
            return null;
        }

        String correctOptionText() {
            for (OptionData option : options) {
                if (option.correct()) {
                    return option.text();
                }
            }
            return null;
        }
    }

    private record PollData(int id, int themeId, String title, String mode, int timeLimitSec,
                            List<QuestionData> questions) {
        boolean quiz() {
            return MODE_QUIZ.equals(mode);
        }
    }

    private record ThemeData(int id, String title, List<PollData> polls) {}

    /**
     * Состояние активного опроса. finished = true означает, что опрос завершён
     * и summaryJson содержит готовый poll_summary (null только внутри finishPoll).
     */
    private record ActiveState(int pollId, int questionIndex, boolean finished, String summaryJson) {}

    private static final List<ThemeData> themes = new ArrayList<>();
    private static final Map<Integer, String> themeTitles = new HashMap<>();
    private static final Map<Integer, PollData> pollsById = new HashMap<>();
    private static final Map<Integer, QuestionData> questionsById = new HashMap<>();
    // Профили из polls.json по названию опроса; пусто, если в файле их нет
    private static final Map<String, Map<String, String>> profilesByPollTitle = new HashMap<>();
    // optionId -> число голосов
    private static final Map<Integer, Integer> optionVotes = new ConcurrentHashMap<>();

    // clientId -> имя. Хранится и после отключения клиента, чтобы имя попало в итоги.
    private static final Map<String, String> clientNames = new ConcurrentHashMap<>();
    private static final AtomicInteger participantCounter = new AtomicInteger();

    private static volatile ActiveState active;
    private static Connection database;

    // Раскрытые вопросы текущей сессии опроса. Меняется под замком класса и замком БД,
    // читается без замков (из saveVote под замком БД).
    private static final Set<Integer> revealedQuestions = ConcurrentHashMap.newKeySet();

    // Таймер викторины. Поля ниже защищены замком класса.
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quiz-timer");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> timerTask;
    private static int timerGeneration;
    private static int secondsLeft;

    /** Подключение клиента: поток вывода, роль, идентификатор и имя из hello. */
    public static final class ClientSession {
        private final PrintWriter out;
        private volatile String role;
        private volatile String clientId;
        private volatile String displayName;

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

        public String displayName() {
            return displayName;
        }

        public void setIdentity(String role, String clientId, String displayName) {
            this.role = role;
            this.clientId = clientId;
            this.displayName = displayName;
        }
    }

    private static final CopyOnWriteArrayList<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        ExecutorService pool = Executors.newCachedThreadPool();

        try (Connection db = DriverManager.getConnection(DB_URL);
             ServerSocket serverSocket = new ServerSocket(PORT)) {

            database = db;
            initDatabase(db);
            loadData(db);
            loadProfiles();
            long quizzes = pollsById.values().stream().filter(PollData::quiz).count();
            System.out.println("База подключена. Тем: " + themes.size() + ", опросов: " + pollsById.size()
                    + " (викторин: " + quizzes + "), вопросов: " + questionsById.size()
                    + ", опросов с профилями: " + profilesByPollTitle.size());

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
            timer.shutdownNow();
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
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        theme_id       INTEGER NOT NULL,
                        title          TEXT NOT NULL,
                        mode           TEXT NOT NULL DEFAULT 'poll',
                        time_limit_sec INTEGER NOT NULL DEFAULT 10,
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
                        is_correct  INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (question_id) REFERENCES questions(id)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS voters (
                        client_id   TEXT NOT NULL,
                        question_id INTEGER NOT NULL,
                        option_id   INTEGER NOT NULL,
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
                "polls", Set.of("id", "theme_id", "title", "mode", "time_limit_sec"),
                "questions", Set.of("id", "poll_id", "order_num", "text"),
                "options", Set.of("id", "question_id", "text", "votes", "is_correct"),
                "voters", Set.of("client_id", "question_id", "option_id"));

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

    private static SeedTheme[] readSeed() throws IOException {
        try (InputStream is = ServerMain.class.getResourceAsStream(POLLS_RESOURCE)) {
            if (is == null) {
                throw new IOException("Ресурс " + POLLS_RESOURCE + " не найден в classpath");
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                SeedTheme[] seed = gson.fromJson(reader, SeedTheme[].class);
                if (seed == null || seed.length == 0) {
                    throw new IOException("Файл " + POLLS_RESOURCE + " пуст");
                }
                return seed;
            }
        }
    }

    private static String normalizeMode(String mode) {
        return MODE_QUIZ.equalsIgnoreCase(mode == null ? "" : mode.trim()) ? MODE_QUIZ : MODE_POLL;
    }

    private static void seedFromResource(Connection db) throws SQLException, IOException {
        SeedTheme[] seed = readSeed();

        db.setAutoCommit(false);
        try {
            for (SeedTheme theme : seed) {
                int themeId = insertReturningId(db, "INSERT INTO themes (title) VALUES (?)", theme.theme());
                for (SeedPoll poll : theme.polls()) {
                    String mode = normalizeMode(poll.mode());
                    boolean quiz = MODE_QUIZ.equals(mode);
                    int limit = poll.timeLimitSeconds() != null && poll.timeLimitSeconds() > 0
                            ? poll.timeLimitSeconds() : DEFAULT_TIME_LIMIT;

                    int pollId = insertReturningId(db,
                            "INSERT INTO polls (theme_id, title, mode, time_limit_sec) VALUES (?, ?, ?, ?)",
                            themeId, poll.title(), mode, limit);
                    int order = 0;
                    for (SeedQuestion question : poll.questions()) {
                        int questionId = insertReturningId(db,
                                "INSERT INTO questions (poll_id, order_num, text) VALUES (?, ?, ?)",
                                pollId, order++, question.text());

                        Integer correctIndex = question.correctIndex();
                        if (quiz && (correctIndex == null || correctIndex < 0
                                || correctIndex >= question.options().size())) {
                            System.err.println("Предупреждение: у вопроса викторины «" + question.text()
                                    + "» не задан корректный correctIndex, правильного ответа не будет");
                            correctIndex = null;
                        }

                        int index = 0;
                        for (String option : question.options()) {
                            boolean correct = quiz && correctIndex != null && correctIndex == index;
                            insertReturningId(db,
                                    "INSERT INTO options (question_id, text, votes, is_correct) VALUES (?, ?, 0, ?)",
                                    questionId, option, correct ? 1 : 0);
                            index++;
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
             ResultSet rs = st.executeQuery(
                     "SELECT id, question_id, text, votes, is_correct FROM options ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                optionsByQuestion.computeIfAbsent(rs.getInt("question_id"), k -> new ArrayList<>())
                        .add(new OptionData(id, rs.getString("text"), rs.getInt("is_correct") == 1));
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
             ResultSet rs = st.executeQuery(
                     "SELECT id, theme_id, title, mode, time_limit_sec FROM polls ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int themeId = rs.getInt("theme_id");
                PollData poll = new PollData(id, themeId, rs.getString("title"),
                        normalizeMode(rs.getString("mode")), rs.getInt("time_limit_sec"),
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

    /**
     * Профили читаются из polls.json при каждом старте, а не из БД, поэтому их можно
     * добавить в файл позже без пересоздания базы. Отсутствие поля или ошибка чтения не критичны.
     */
    private static void loadProfiles() {
        try {
            for (SeedTheme theme : readSeed()) {
                if (theme.polls() == null) {
                    continue;
                }
                for (SeedPoll poll : theme.polls()) {
                    if (poll.profiles() != null && !poll.profiles().isEmpty()) {
                        profilesByPollTitle.put(poll.title(), Map.copyOf(poll.profiles()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Профили из " + POLLS_RESOURCE + " не загружены, будут использованы универсальные: "
                    + e.getMessage());
        }
    }

    // ---------- Имена участников ----------

    /** Сохраняет имя клиента. Если имя не передано, назначает «Участник N» (один раз на clientId). */
    public static String registerName(String clientId, String requested) {
        if (requested != null && !requested.isBlank()) {
            String name = requested.trim();
            if (name.length() > MAX_NAME_LENGTH) {
                name = name.substring(0, MAX_NAME_LENGTH);
            }
            clientNames.put(clientId, name);
            return name;
        }
        return clientNames.computeIfAbsent(clientId, k -> "Участник " + participantCounter.incrementAndGet());
    }

    // ---------- Каталог и состояние ----------

    public static CatalogResponse buildCatalog() {
        List<CatalogTheme> result = new ArrayList<>();
        for (ThemeData theme : themes) {
            List<CatalogPoll> polls = new ArrayList<>();
            for (PollData poll : theme.polls()) {
                polls.add(new CatalogPoll(poll.id(), poll.title(), poll.questions().size(), poll.mode()));
            }
            result.add(new CatalogTheme(theme.id(), theme.title(), polls));
        }
        return new CatalogResponse("catalog", result);
    }

    public static String catalogJson() {
        return gson.toJson(buildCatalog());
    }

    /**
     * JSON текущего состояния: waiting, active_question или poll_summary.
     * Метод синхронизирован, чтобы не отдать состояние в середине finishPoll или смены вопроса.
     */
    public static synchronized String currentStateJson() {
        ActiveState state = active;
        if (state == null) {
            return gson.toJson(new WaitingResponse("waiting"));
        }
        if (state.finished()) {
            return state.summaryJson();
        }
        PollData poll = pollsById.get(state.pollId());
        QuestionData question = poll.questions().get(state.questionIndex());

        List<OptionResult> options = new ArrayList<>();
        for (OptionData option : question.options()) {
            options.add(new OptionResult(option.id(), option.text(), optionVotes.getOrDefault(option.id(), 0)));
        }

        boolean quiz = poll.quiz();
        boolean revealed = quiz && revealedQuestions.contains(question.id());
        Integer seconds = null;
        if (quiz) {
            if (revealed) {
                seconds = 0;
            } else if (timerTask != null) {
                seconds = secondsLeft;
            }
        }
        // Правильный ответ уходит клиентам только после раскрытия
        Integer correctOptionId = revealed ? question.correctOptionId() : null;

        return gson.toJson(new ActiveQuestionResponse("active_question", poll.id(),
                themeTitles.get(poll.themeId()), poll.title(), question.id(), state.questionIndex(),
                poll.questions().size(), question.text(), options,
                poll.mode(), revealed, seconds, correctOptionId));
    }

    /**
     * Можно ли принять голос по вопросу. Намеренно не synchronized: вызывается из ClientHandler
     * под замком БД, а finishPoll и reveal берут замки в обратном порядке (класс, затем БД).
     */
    public static VoteGate voteGate(int questionId) {
        ActiveState state = active;
        if (state == null) {
            return VoteGate.NO_POLL;
        }
        if (state.finished()) {
            return VoteGate.FINISHED;
        }
        PollData poll = pollsById.get(state.pollId());
        QuestionData current = poll.questions().get(state.questionIndex());
        if (current.id() != questionId) {
            return VoteGate.WRONG_QUESTION;
        }
        if (poll.quiz() && revealedQuestions.contains(questionId)) {
            return VoteGate.CLOSED;
        }
        return VoteGate.OPEN;
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
        cancelTimer();
        synchronized (database) {
            revealedQuestions.clear();
            active = new ActiveState(pollId, 0, false, null);
        }
        enterQuestion();
        return true;
    }

    public static synchronized boolean setQuestion(int questionIndex) {
        ActiveState state = active;
        if (state == null || state.finished()) {
            return false;
        }
        int total = pollsById.get(state.pollId()).questions().size();
        if (questionIndex < 0 || questionIndex >= total) {
            return false;
        }
        cancelTimer();
        active = new ActiveState(state.pollId(), questionIndex, false, null);
        enterQuestion();
        return true;
    }

    public static synchronized void closePoll() {
        cancelTimer();
        active = null;
        broadcastState();
    }

    /**
     * Завершает опрос: закрывает приём голосов, читает все голоса из SQLite,
     * считает итоги и рассылает poll_summary. Порядок замков: класс, затем БД.
     */
    public static synchronized FinishResult finishPoll() throws SQLException {
        ActiveState state = active;
        if (state == null) {
            return FinishResult.NO_ACTIVE_POLL;
        }
        if (state.finished()) {
            return FinishResult.ALREADY_FINISHED;
        }
        PollData poll = pollsById.get(state.pollId());

        String summaryJson;
        synchronized (database) {
            // Сначала закрываем приём голосов: голос, который успеет в БД раньше, попадёт в итоги,
            // а все последующие saveVote увидят finished и откажут.
            active = new ActiveState(state.pollId(), state.questionIndex(), true, null);
            try {
                summaryJson = gson.toJson(buildSummary(poll));
            } catch (SQLException | RuntimeException e) {
                active = state;
                throw e;
            }
        }

        cancelTimer();
        active = new ActiveState(state.pollId(), state.questionIndex(), true, summaryJson);
        broadcastState();
        return FinishResult.OK;
    }

    /**
     * Учитывает принятый (уже записанный в БД) голос в памяти. В викторине проверяет досрочное
     * раскрытие (проголосовали все подключённые участники), иначе рассылает новое состояние.
     */
    public static synchronized void voteAccepted(int questionId, int optionId) {
        optionVotes.merge(optionId, 1, Integer::sum);
        ActiveState state = active;
        if (state == null || state.finished()) {
            return;
        }
        if (isRunningQuizQuestion(questionId) && allConnectedVoted(questionId)) {
            reveal();
        } else {
            broadcastState();
        }
    }

    // ---------- Таймер и раскрытие ответа (всё ниже вызывается под замком класса) ----------

    /** Входит в текущий вопрос: для ещё не раскрытого вопроса викторины запускает отсчёт. */
    private static synchronized void enterQuestion() {
        ActiveState state = active;
        PollData poll = pollsById.get(state.pollId());
        QuestionData question = poll.questions().get(state.questionIndex());

        if (poll.quiz() && !revealedQuestions.contains(question.id())) {
            startTimer(poll.timeLimitSec());
            // Случай повторного запуска: все подключённые уже отвечали на этот вопрос раньше
            if (allConnectedVoted(question.id())) {
                reveal();
                return;
            }
        }
        broadcastState();
    }

    private static synchronized void startTimer(int seconds) {
        cancelTimer();
        int generation = timerGeneration;
        int questionId = currentQuestionId();
        secondsLeft = seconds;
        timerTask = timer.scheduleAtFixedRate(() -> {
            try {
                onTick(generation, questionId);
            } catch (RuntimeException e) {
                // Необработанное исключение остановило бы периодическую задачу без следа
                System.err.println("Ошибка таймера: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static synchronized void cancelTimer() {
        if (timerTask != null) {
            timerTask.cancel(false);
            timerTask = null;
        }
        // Уже запущенный тик дождётся замка и увидит, что поколение сменилось
        timerGeneration++;
    }

    private static synchronized void onTick(int generation, int questionId) {
        if (generation != timerGeneration || timerTask == null) {
            return;
        }
        ActiveState state = active;
        if (state == null || state.finished()) {
            return;
        }
        if (secondsLeft > 0) {
            secondsLeft--;
        }
        if (secondsLeft > 0) {
            broadcastRaw(gson.toJson(new TimerTick("timer_tick", questionId, secondsLeft)));
        } else {
            reveal();
        }
    }

    /**
     * Раскрывает ответ на текущий вопрос: отменяет таймер, закрывает приём голосов
     * и рассылает active_question с revealed = true. Метку ставим под замком БД,
     * чтобы saveVote, уже державший замок, успел дописать свой голос до закрытия.
     */
    private static synchronized void reveal() {
        ActiveState state = active;
        if (state == null || state.finished()) {
            return;
        }
        int questionId = currentQuestionId();
        cancelTimer();
        synchronized (database) {
            revealedQuestions.add(questionId);
        }
        broadcastState();
    }

    private static int currentQuestionId() {
        ActiveState state = active;
        return pollsById.get(state.pollId()).questions().get(state.questionIndex()).id();
    }

    private static boolean isRunningQuizQuestion(int questionId) {
        ActiveState state = active;
        if (state == null || state.finished()) {
            return false;
        }
        PollData poll = pollsById.get(state.pollId());
        return poll.quiz()
                && currentQuestionId() == questionId
                && !revealedQuestions.contains(questionId)
                && timerTask != null;
    }

    /** true, если подключённых участников больше нуля и каждый уже есть в voters по этому вопросу. */
    private static boolean allConnectedVoted(int questionId) {
        Set<String> connected = new HashSet<>();
        for (ClientSession session : sessions) {
            if ("client".equals(session.role()) && session.clientId() != null) {
                connected.add(session.clientId());
            }
        }
        if (connected.isEmpty() || database == null) {
            return false;
        }

        Set<String> voted = new HashSet<>();
        synchronized (database) {
            try (PreparedStatement ps = database.prepareStatement(
                    "SELECT client_id FROM voters WHERE question_id = ?")) {
                ps.setInt(1, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        voted.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Ошибка проверки голосов: " + e.getMessage());
                return false;
            }
        }
        return voted.containsAll(connected);
    }

    /** После отключения участника оставшиеся могли оказаться все проголосовавшими. */
    private static synchronized void checkEarlyReveal() {
        ActiveState state = active;
        if (state == null || state.finished()) {
            return;
        }
        int questionId = currentQuestionId();
        if (isRunningQuizQuestion(questionId) && allConnectedVoted(questionId)) {
            reveal();
        }
    }

    // ---------- Итоги ----------

    /** Вызывается под замком БД. */
    private static PollSummaryResponse buildSummary(PollData poll) throws SQLException {
        boolean quiz = poll.quiz();

        // clientId -> (questionId -> optionId), в порядке первого появления
        Map<String, Map<Integer, Integer>> answersByClient = new LinkedHashMap<>();
        // optionId -> число голосов в рамках этого опроса
        Map<Integer, Integer> optionCounts = new HashMap<>();

        try (PreparedStatement ps = database.prepareStatement("""
                SELECT v.client_id, v.question_id, v.option_id
                FROM voters v
                JOIN questions q ON q.id = v.question_id
                WHERE q.poll_id = ?""")) {
            ps.setInt(1, poll.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String clientId = rs.getString("client_id");
                    int questionId = rs.getInt("question_id");
                    int optionId = rs.getInt("option_id");
                    answersByClient.computeIfAbsent(clientId, k -> new HashMap<>()).put(questionId, optionId);
                    optionCounts.merge(optionId, 1, Integer::sum);
                }
            }
        }

        // В викторине в рейтинг попадают и подключённые участники без единого ответа
        if (quiz) {
            for (ClientSession session : sessions) {
                if ("client".equals(session.role()) && session.clientId() != null) {
                    answersByClient.putIfAbsent(session.clientId(), new HashMap<>());
                }
            }
        }

        int maxOptions = 0;
        List<SummaryQuestion> summaryQuestions = new ArrayList<>();
        Map<Integer, Integer> leaderVotes = new HashMap<>(); // questionId -> максимум голосов
        int number = 0;
        for (QuestionData question : poll.questions()) {
            number++;
            maxOptions = Math.max(maxOptions, question.options().size());

            List<SummaryOption> options = new ArrayList<>();
            int total = 0;
            int max = 0;
            for (OptionData option : question.options()) {
                int votes = optionCounts.getOrDefault(option.id(), 0);
                options.add(new SummaryOption(option.id(), option.text(), votes));
                total += votes;
                max = Math.max(max, votes);
            }
            leaderVotes.put(question.id(), max);
            summaryQuestions.add(new SummaryQuestion(number, question.text(), total, options,
                    quiz ? question.correctOptionId() : null,
                    quiz ? question.correctOptionText() : null));
        }

        List<SummaryParticipant> participants = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Integer>> entry : answersByClient.entrySet()) {
            SummaryParticipant participant = buildParticipant(poll, entry.getKey(), entry.getValue(),
                    maxOptions, optionCounts, leaderVotes);
            if (participant != null) {
                participants.add(participant);
            }
        }

        if (quiz) {
            participants.sort((a, b) -> {
                int byScore = Integer.compare(b.correctCount(), a.correctCount());
                return byScore != 0 ? byScore : compareNames(a.displayName(), b.displayName());
            });
        } else {
            participants.sort((a, b) -> compareNames(a.displayName(), b.displayName()));
        }

        return new PollSummaryResponse("poll_summary", poll.id(), themeTitles.get(poll.themeId()),
                poll.title(), poll.mode(), summaryQuestions, participants);
    }

    private static SummaryParticipant buildParticipant(PollData poll, String clientId,
                                                       Map<Integer, Integer> chosen, int maxOptions,
                                                       Map<Integer, Integer> optionCounts,
                                                       Map<Integer, Integer> leaderVotes) {
        boolean quiz = poll.quiz();
        int[] indexCounts = new int[maxOptions];
        List<SummaryAnswer> answers = new ArrayList<>();
        int matches = 0;
        int answered = 0;
        int correctCount = 0;

        int number = 0;
        for (QuestionData question : poll.questions()) {
            number++;
            Integer optionId = chosen.get(question.id());
            int index = -1;
            if (optionId != null) {
                for (int i = 0; i < question.options().size(); i++) {
                    if (question.options().get(i).id() == optionId) {
                        index = i;
                        break;
                    }
                }
            }

            if (index < 0) {
                if (quiz) {
                    answers.add(new SummaryAnswer(number, question.text(), "Нет ответа",
                            false, question.correctOptionText()));
                }
                continue;
            }

            indexCounts[index]++;
            answered++;

            Boolean correct = null;
            String correctText = null;
            if (quiz) {
                Integer correctId = question.correctOptionId();
                boolean isCorrect = correctId != null && correctId.intValue() == optionId;
                if (isCorrect) {
                    correctCount++;
                }
                correct = isCorrect;
                correctText = question.correctOptionText();
            }
            answers.add(new SummaryAnswer(number, question.text(), question.options().get(index).text(),
                    correct, correctText));

            // Совпадение с большинством: у выбранного варианта максимум голосов в вопросе (при ничьей совпадают все лидеры)
            int max = leaderVotes.getOrDefault(question.id(), 0);
            if (max > 0 && optionCounts.getOrDefault(optionId, 0) == max) {
                matches++;
            }
        }

        if (answered == 0 && !quiz) {
            return null;
        }
        int matchPercent = answered == 0 ? 0 : (int) Math.round(matches * 100.0 / answered);

        List<Integer> counts = new ArrayList<>();
        for (int count : indexCounts) {
            counts.add(count);
        }
        String name = clientNames.getOrDefault(clientId, "Участник");
        int totalQuestions = poll.questions().size();

        if (quiz) {
            int grade10 = totalQuestions == 0 ? 0 : (int) Math.round(correctCount * 10.0 / totalQuestions);
            String verdict = buildQuizVerdict(correctCount, totalQuestions, grade10, answered);
            return new SummaryParticipant(clientId, name, answered, totalQuestions, counts,
                    matchPercent, verdict, answers, correctCount, grade10);
        }

        int best = 0;
        for (int count : indexCounts) {
            best = Math.max(best, count);
        }
        int bestIndex = -1;
        int leaders = 0;
        for (int i = 0; i < indexCounts.length; i++) {
            if (indexCounts[i] == best) {
                leaders++;
                bestIndex = i;
            }
        }
        boolean mixed = leaders != 1;

        String verdict = buildVerdict(poll, mixed ? -1 : bestIndex, best, answered, matchPercent);
        return new SummaryParticipant(clientId, name, answered, totalQuestions, counts,
                matchPercent, verdict, answers, null, null);
    }

    private static String buildQuizVerdict(int correct, int total, int grade10, int answered) {
        String head = "Правильных ответов: " + correct + " из " + total + ", оценка " + grade10 + " из 10.";
        if (answered == 0) {
            return head + " Вы не ответили ни на один вопрос.";
        }
        String tail;
        if (grade10 >= 9) {
            tail = " Почти без ошибок.";
        } else if (grade10 >= 7) {
            tail = " Уверенный результат, несколько вопросов стоит повторить.";
        } else if (grade10 >= 4) {
            tail = " Базу вы знаете, но пробелы заметны.";
        } else {
            tail = " Тема пока даётся с трудом, имеет смысл вернуться к материалу.";
        }
        if (answered < total) {
            tail += " Без ответа осталось вопросов: " + (total - answered) + ".";
        }
        return head + tail;
    }

    /** dominantIndex = -1 означает смешанный тип (поровну между несколькими вариантами). */
    private static String buildVerdict(PollData poll, int dominantIndex, int dominantCount,
                                       int answered, int matchPercent) {
        Map<String, String> profiles = profilesByPollTitle.get(poll.title());
        if (profiles != null) {
            String key = dominantIndex < 0 ? "mixed" : "option" + (dominantIndex + 1);
            String text = profiles.get(key);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }

        String closeness;
        if (matchPercent >= 70) {
            closeness = "Ваша позиция близка к общему мнению группы.";
        } else if (matchPercent <= 30) {
            closeness = "Ваша позиция заметно отличается от мнения большинства.";
        } else {
            closeness = "Ваша позиция лишь отчасти совпадает с мнением большинства.";
        }

        String lead;
        if (dominantIndex < 0) {
            lead = "Ваши ответы распределились между вариантами примерно поровну, "
                    + "устойчивого предпочтения нет.";
        } else {
            lead = "Чаще всего вы выбирали вариант №" + (dominantIndex + 1)
                    + " (" + dominantCount + " из " + answered + ").";
        }
        return lead + " С большинством вы совпали в " + matchPercent + "% вопросов. " + closeness;
    }

    /** Сортировка «Участник 2» перед «Участник 10». */
    private static int compareNames(String a, String b) {
        int byPrefix = a.replaceAll("\\d+$", "").compareToIgnoreCase(b.replaceAll("\\d+$", ""));
        if (byPrefix != 0) {
            return byPrefix;
        }
        int byNumber = Long.compare(trailingNumber(a), trailingNumber(b));
        return byNumber != 0 ? byNumber : a.compareTo(b);
    }

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    private static long trailingNumber(String s) {
        Matcher m = TRAILING_NUMBER.matcher(s);
        if (!m.find()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // ---------- Клиенты ----------

    public static void addSession(ClientSession session) {
        sessions.add(session);
    }

    public static void removeSession(ClientSession session) {
        sessions.remove(session);
        checkEarlyReveal();
    }

    /** Рассылка без записи в консоль (для ежесекундных тиков). */
    private static synchronized void broadcastRaw(String json) {
        for (ClientSession session : sessions) {
            if (session.role() == null) {
                continue;
            }
            session.send(json);
            if (session.hasError()) {
                sessions.remove(session);
            }
        }
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
        String shown = json.length() > 200 ? json.substring(0, 200) + "… (" + json.length() + " симв.)" : json;
        System.out.println("Рассылка (получателей: " + delivered + "): " + shown);
    }
}