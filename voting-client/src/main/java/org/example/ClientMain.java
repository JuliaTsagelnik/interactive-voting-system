// ClientMain.java
package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ClientMain extends Application {

    // ---------- Запросы к серверу ----------

    private record HelloRequest(String action, String role, String clientId, String displayName) {}
    private record ActionRequest(String action) {}
    private record OpenPollRequest(String action, int pollId) {}
    private record SetQuestionRequest(String action, int questionIndex) {}
    private record VoteRequest(String action, int questionId, int optionId, String clientId) {}

    // ---------- Ответы сервера ----------

    private record CatalogPoll(int id, String title, int questionCount) {
        @Override
        public String toString() {
            return title + " (вопросов: " + questionCount + ")";
        }
    }

    private record CatalogTheme(int id, String title, List<CatalogPoll> polls) {
        @Override
        public String toString() {
            return title;
        }
    }

    private record CatalogMessage(String type, List<CatalogTheme> themes) {}
    private record OptionResult(int id, String text, int votes) {}
    private record ActiveQuestion(String type, int pollId, String theme, String pollTitle,
                                  int questionId, int questionIndex, int totalQuestions,
                                  String text, List<OptionResult> options) {}
    private record StatusMessage(String type, String code, String message, Integer questionId) {}

    private record SummaryOption(int id, String text, int votes) {}
    private record SummaryQuestion(int orderNum, String text, int totalVotes, List<SummaryOption> options) {}
    private record SummaryAnswer(int questionNumber, String questionText, String optionText) {}
    private record SummaryParticipant(String clientId, String displayName, int answeredCount, int totalQuestions,
                                      List<Integer> optionIndexCounts, int majorityMatchPercent,
                                      String verdict, List<SummaryAnswer> answers) {}
    private record PollSummary(String type, int pollId, String theme, String pollTitle,
                               List<SummaryQuestion> questions, List<SummaryParticipant> participants) {}

    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final String NAME_ARG = "--name=";
    private static final String CARD_STYLE =
            "-fx-border-color: #c8c8c8; -fx-border-radius: 6; -fx-background-radius: 6; "
                    + "-fx-background-color: #fafafa; -fx-padding: 10;";

    private final String clientId = UUID.randomUUID().toString();
    private final Gson gson = new Gson();

    // Режим и имя определяются в start() по аргументам запуска
    private boolean host;
    private String displayName;

    // Сеть
    private volatile boolean running = true;
    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;

    // Состояние
    private ActiveQuestion current;
    private final Set<Integer> votedQuestions = new HashSet<>();
    private final List<PieChart.Data> pieData = new ArrayList<>();
    private final List<Button> optionButtons = new ArrayList<>();

    // Три экрана правой области (одновременно виден один)
    private final StackPane waitingView = new StackPane();
    private final Label waitingLabel = new Label();
    private ScrollPane activeView;
    private final StackPane summaryView = new StackPane();

    // Общие элементы
    private final Label themeLabel = label("", "-fx-font-size: 12px; -fx-text-fill: #777777;");
    private final Label pollLabel = label("", "-fx-font-size: 15px; -fx-font-weight: bold;");
    private final Label counterLabel = label("", "-fx-font-size: 12px; -fx-text-fill: #444444;");
    private final Label questionLabel = label("", "-fx-font-size: 17px; -fx-font-weight: bold;");
    private final Label statusLabel = label("", "-fx-font-size: 13px;");

    // Элементы ведущего
    private final PieChart chart = new PieChart();
    private final ListView<CatalogTheme> themesList = new ListView<>();
    private final ListView<CatalogPoll> pollsList = new ListView<>();
    private final Button openBtn = new Button("Открыть опрос");
    private final Button prevBtn = new Button("← Предыдущий");
    private final Button nextBtn = new Button("Следующий →");
    private final Button finishBtn = new Button("Завершить и показать итоги");
    private final Button closeBtn = new Button("Закрыть опрос");
    private final VBox resultsBox = new VBox(4);

    // Элементы участника
    private final VBox answerBox = new VBox(8);

    @Override
    public void start(Stage stage) {
        List<String> raw = getParameters().getRaw();
        host = raw.contains("--host");
        for (String arg : raw) {
            if (arg.startsWith(NAME_ARG) && arg.length() > NAME_ARG.length()) {
                displayName = arg.substring(NAME_ARG.length()).trim();
            }
        }

        Region content;
        if (host) {
            content = buildHostLayout();
            stage.setTitle("Голосование — Ведущий");
            stage.setMinWidth(540);
            stage.setMinHeight(380);
            stage.setScene(new Scene(content, 820, 560));
        } else {
            content = buildParticipantLayout();
            stage.setTitle("Голосование — " + (displayName != null && !displayName.isEmpty()
                    ? displayName : "Участник"));
            stage.setMinWidth(300);
            stage.setMinHeight(240);
            stage.setScene(new Scene(content, 430, 330));
        }
        stage.show();

        showWaiting();
        connect();
    }

    // ---------- Построение интерфейса ----------

    private static Label label(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        if (style != null && !style.isEmpty()) {
            l.setStyle(style);
        }
        return l;
    }

    private static ScrollPane scroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent;");
        return sp;
    }

    private VBox header() {
        return new VBox(4, themeLabel, pollLabel, counterLabel, questionLabel);
    }

    private void buildWaitingView() {
        waitingLabel.setWrapText(true);
        waitingLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #666666;");
        waitingLabel.setAlignment(Pos.CENTER);
        waitingLabel.setTextAlignment(TextAlignment.CENTER);
        waitingView.setPadding(new Insets(15));
        waitingView.getChildren().add(waitingLabel);
    }

    private Region buildHostLayout() {
        buildWaitingView();

        // Каталог слева
        enableWrap(themesList);
        enableWrap(pollsList);
        themesList.getSelectionModel().selectedItemProperty().addListener((obs, old, theme) ->
                pollsList.setItems(theme == null
                        ? FXCollections.observableArrayList()
                        : FXCollections.observableArrayList(theme.polls())));
        openBtn.setDisable(true);
        pollsList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, poll) -> openBtn.setDisable(poll == null || !connected()));
        openBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setOnAction(e -> {
            CatalogPoll poll = pollsList.getSelectionModel().getSelectedItem();
            if (poll != null) {
                statusLabel.setText("");
                sendJson(new OpenPollRequest("host_open_poll", poll.id()));
            }
        });

        VBox.setVgrow(themesList, Priority.ALWAYS);
        VBox.setVgrow(pollsList, Priority.ALWAYS);
        VBox left = new VBox(6,
                new Label("Темы"), themesList,
                new Label("Опросы выбранной темы"), pollsList,
                openBtn);
        left.setPadding(new Insets(10));
        left.setMinWidth(0);

        // Активный вопрос
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMinSize(140, 140);
        chart.setPrefSize(240, 220);
        VBox.setVgrow(chart, Priority.ALWAYS);

        ScrollPane resultsScroll = scroll(resultsBox);
        resultsScroll.setPrefHeight(110);
        resultsScroll.setMinHeight(60);
        resultsBox.setPadding(new Insets(2));

        prevBtn.setOnAction(e -> {
            if (current != null) {
                sendJson(new SetQuestionRequest("host_set_question", current.questionIndex() - 1));
            }
        });
        nextBtn.setOnAction(e -> {
            if (current != null) {
                sendJson(new SetQuestionRequest("host_set_question", current.questionIndex() + 1));
            }
        });
        finishBtn.setOnAction(e -> sendJson(new ActionRequest("host_finish_poll")));
        closeBtn.setOnAction(e -> sendJson(new ActionRequest("host_close_poll")));

        FlowPane controls = new FlowPane(8, 8, prevBtn, nextBtn, finishBtn, closeBtn);
        controls.setAlignment(Pos.CENTER);

        VBox activeContent = new VBox(8, header(), chart, resultsScroll, controls);
        activeContent.setPadding(new Insets(10));
        activeView = scroll(activeContent);
        activeView.setFitToHeight(true);

        StackPane right = new StackPane(waitingView, activeView, summaryView);
        right.setMinWidth(0);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.34);
        SplitPane.setResizableWithParent(left, false);

        statusLabel.setPadding(new Insets(4, 10, 6, 10));
        BorderPane root = new BorderPane();
        root.setCenter(split);
        root.setBottom(statusLabel);
        return root;
    }

    private Region buildParticipantLayout() {
        buildWaitingView();

        answerBox.setFillWidth(true);
        VBox activeContent = new VBox(10, header(), answerBox);
        activeContent.setPadding(new Insets(10));
        activeView = scroll(activeContent);

        StackPane center = new StackPane(waitingView, activeView, summaryView);

        statusLabel.setPadding(new Insets(4, 10, 6, 10));
        BorderPane root = new BorderPane();
        root.setCenter(center);
        root.setBottom(statusLabel);
        return root;
    }

    /** Ячейки списка переносят длинный текст, а не обрезают его многоточием. */
    private static <T> void enableWrap(ListView<T> list) {
        list.setCellFactory(lv -> new ListCell<>() {
            {
                setWrapText(true);
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
    }

    private void showOnly(Node view) {
        for (Node n : List.of(waitingView, activeView, summaryView)) {
            boolean visible = n == view;
            n.setVisible(visible);
            n.setManaged(visible);
        }
    }

    // ---------- Сеть ----------

    private boolean connected() {
        return out != null && socket != null && !socket.isClosed();
    }

    private void connect() {
        try {
            socket = new Socket(HOST, PORT);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            readerThread = new Thread(() -> readLoop(in), "server-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            sendJson(new HelloRequest("hello", host ? "host" : "client", clientId,
                    host ? null : displayName));
        } catch (IOException e) {
            setConnectionLost();
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Не удалось подключиться к " + HOST + ":" + PORT + "\n" + e.getMessage());
            alert.setHeaderText("Сервер недоступен");
            alert.show();
        }
    }

    private void sendJson(Object request) {
        if (out == null) {
            return;
        }
        out.println(gson.toJson(request));
        if (out.checkError()) {
            setConnectionLost();
        }
    }

    private void readLoop(BufferedReader in) {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                String payload = line;
                Platform.runLater(() -> handleServerMessage(payload));
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Ошибка чтения: " + e.getMessage());
            }
        } finally {
            if (running) {
                Platform.runLater(this::setConnectionLost);
            }
        }
    }

    private void setConnectionLost() {
        statusLabel.setText("Соединение с сервером потеряно");
        openBtn.setDisable(true);
        prevBtn.setDisable(true);
        nextBtn.setDisable(true);
        finishBtn.setDisable(true);
        closeBtn.setDisable(true);
        optionButtons.forEach(b -> b.setDisable(true));
    }

    // ---------- Разбор сообщений сервера (выполняется в потоке JavaFX) ----------

    private void handleServerMessage(String line) {
        JsonObject obj;
        try {
            obj = JsonParser.parseString(line).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            System.err.println("Некорректный JSON от сервера: " + e.getMessage());
            return;
        }
        JsonElement type = obj.get("type");
        if (type == null || type.isJsonNull()) {
            return;
        }

        try {
            switch (type.getAsString()) {
                case "catalog" -> {
                    if (host) {
                        showCatalog(gson.fromJson(obj, CatalogMessage.class));
                    }
                }
                case "waiting" -> showWaiting();
                case "active_question" -> showActive(gson.fromJson(obj, ActiveQuestion.class));
                case "poll_summary" -> showSummary(gson.fromJson(obj, PollSummary.class));
                case "vote_ok", "error" -> handleStatus(gson.fromJson(obj, StatusMessage.class));
                default -> { }
            }
        } catch (JsonSyntaxException e) {
            System.err.println("Не удалось разобрать сообщение: " + e.getMessage());
        }
    }

    private void showCatalog(CatalogMessage catalog) {
        if (catalog == null || catalog.themes() == null) {
            return;
        }
        themesList.setItems(FXCollections.observableArrayList(catalog.themes()));
        if (!catalog.themes().isEmpty()) {
            themesList.getSelectionModel().selectFirst();
        }
    }

    private void resetQuestionState() {
        current = null;
        pieData.clear();
        chart.getData().clear();
        optionButtons.clear();
        answerBox.getChildren().clear();
        resultsBox.getChildren().clear();
    }

    private void showWaiting() {
        resetQuestionState();
        waitingLabel.setText(host
                ? "Выберите тему и опрос слева, затем нажмите «Открыть опрос»."
                : "Ожидание выбора опроса ведущим...");
        showOnly(waitingView);
        statusLabel.setText("");
    }

    private void showActive(ActiveQuestion q) {
        if (q == null || q.options() == null) {
            return;
        }
        boolean sameQuestion = current != null
                && current.questionId() == q.questionId()
                && current.options().size() == q.options().size();
        current = q;

        showOnly(activeView);

        themeLabel.setText(q.theme());
        pollLabel.setText(q.pollTitle());
        counterLabel.setText("Вопрос " + (q.questionIndex() + 1) + " из " + q.totalQuestions());
        questionLabel.setText(q.text());

        if (host) {
            if (sameQuestion && pieData.size() == q.options().size()) {
                for (int i = 0; i < q.options().size(); i++) {
                    pieData.get(i).setPieValue(q.options().get(i).votes());
                }
            } else {
                pieData.clear();
                chart.getData().clear();
                for (int i = 0; i < q.options().size(); i++) {
                    PieChart.Data slice = new PieChart.Data("Вариант " + (i + 1), q.options().get(i).votes());
                    pieData.add(slice);
                    chart.getData().add(slice);
                }
            }
            rebuildResults(q);
            prevBtn.setDisable(q.questionIndex() <= 0);
            nextBtn.setDisable(q.questionIndex() >= q.totalQuestions() - 1);
            finishBtn.setDisable(false);
            closeBtn.setDisable(false);
        } else {
            if (!sameQuestion) {
                rebuildAnswerButtons(q);
            }
            applyVotedState();
        }
    }

    private void rebuildResults(ActiveQuestion q) {
        resultsBox.getChildren().clear();
        int total = 0;
        for (int i = 0; i < q.options().size(); i++) {
            OptionResult option = q.options().get(i);
            total += option.votes();
            resultsBox.getChildren().add(label((i + 1) + ". " + option.text() + " — " + option.votes(), null));
        }
        resultsBox.getChildren().add(label("Всего голосов: " + total, "-fx-text-fill: #777777;"));
    }

    private void rebuildAnswerButtons(ActiveQuestion q) {
        optionButtons.clear();
        answerBox.getChildren().clear();
        for (int i = 0; i < q.options().size(); i++) {
            OptionResult option = q.options().get(i);
            Button button = new Button((i + 1) + ". " + option.text());
            button.setWrapText(true);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinHeight(Region.USE_PREF_SIZE);
            button.setStyle("-fx-font-size: 13px; -fx-alignment: CENTER_LEFT; -fx-padding: 6 10 6 10;");
            button.setOnAction(e -> onVoteClicked(option.id()));
            optionButtons.add(button);
            answerBox.getChildren().add(button);
        }
    }

    // ---------- Итоги ----------

    private void showSummary(PollSummary s) {
        if (s == null) {
            return;
        }
        resetQuestionState();
        statusLabel.setText("");
        summaryView.getChildren().setAll(host ? buildHostSummary(s) : buildParticipantSummary(s));
        showOnly(summaryView);
    }

    private Node buildHostSummary(PollSummary s) {
        // Портреты участников
        VBox portraits = new VBox(8);
        portraits.setPadding(new Insets(8));
        List<SummaryParticipant> participants = s.participants() == null ? List.of() : s.participants();
        if (participants.isEmpty()) {
            portraits.getChildren().add(label("В этом опросе никто не проголосовал.", null));
        }
        for (SummaryParticipant p : participants) {
            portraits.getChildren().add(participantCard(p, p.displayName()));
        }

        // Сводка по вопросам
        VBox questions = new VBox(8);
        questions.setPadding(new Insets(8));
        if (s.questions() != null) {
            for (SummaryQuestion q : s.questions()) {
                VBox card = new VBox(3);
                card.setStyle(CARD_STYLE);
                card.getChildren().add(label(q.orderNum() + ". " + q.text(), "-fx-font-weight: bold;"));
                for (int i = 0; i < q.options().size(); i++) {
                    SummaryOption o = q.options().get(i);
                    int percent = q.totalVotes() == 0 ? 0 : (int) Math.round(o.votes() * 100.0 / q.totalVotes());
                    card.getChildren().add(label((i + 1) + ") " + o.text() + " — " + o.votes()
                            + " (" + percent + "%)", null));
                }
                card.getChildren().add(label("Всего голосов: " + q.totalVotes(), "-fx-text-fill: #777777;"));
                questions.getChildren().add(card);
            }
        }

        TabPane tabs = new TabPane(
                new Tab("Портреты участников", scroll(portraits)),
                new Tab("Сводка по вопросам", scroll(questions)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Label title = label(s.theme() + " — " + s.pollTitle(), "-fx-font-size: 14px; -fx-font-weight: bold;");
        title.setPadding(new Insets(8, 10, 4, 10));

        Button back = new Button("Вернуться к выбору опроса");
        back.setOnAction(e -> sendJson(new ActionRequest("host_close_poll")));
        FlowPane bottom = new FlowPane(back);
        bottom.setAlignment(Pos.CENTER);
        bottom.setPadding(new Insets(8));

        BorderPane pane = new BorderPane();
        pane.setTop(title);
        pane.setCenter(tabs);
        pane.setBottom(bottom);
        return pane;
    }

    private Node buildParticipantSummary(PollSummary s) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));
        box.getChildren().add(label("Ваш итог по опросу", "-fx-font-size: 17px; -fx-font-weight: bold;"));
        box.getChildren().add(label(s.pollTitle(), "-fx-text-fill: #555555;"));

        SummaryParticipant mine = null;
        if (s.participants() != null) {
            for (SummaryParticipant p : s.participants()) {
                if (clientId.equals(p.clientId())) {
                    mine = p;
                    break;
                }
            }
        }
        if (mine == null) {
            box.getChildren().add(label("Вы не участвовали в этом опросе.", "-fx-font-size: 14px;"));
        } else {
            box.getChildren().add(participantCard(mine, mine.displayName()));
        }
        return scroll(box);
    }

    private VBox participantCard(SummaryParticipant p, String name) {
        VBox card = new VBox(4);
        card.setStyle(CARD_STYLE);
        card.getChildren().add(label(name, "-fx-font-size: 15px; -fx-font-weight: bold;"));
        card.getChildren().add(label("Отвечено вопросов: " + p.answeredCount() + " из " + p.totalQuestions(), null));
        card.getChildren().add(label("Совпадение с большинством: " + p.majorityMatchPercent() + "%", null));

        StringBuilder dist = new StringBuilder("Распределение ответов: ");
        List<Integer> counts = p.optionIndexCounts() == null ? List.of() : p.optionIndexCounts();
        for (int i = 0; i < counts.size(); i++) {
            if (i > 0) {
                dist.append("  ·  ");
            }
            dist.append("вариант ").append(i + 1).append(": ").append(counts.get(i));
        }
        card.getChildren().add(label(dist.toString(), null));

        if (p.verdict() != null && !p.verdict().isBlank()) {
            card.getChildren().add(label(p.verdict(), "-fx-font-style: italic; -fx-padding: 4 0 4 0;"));
        }

        if (p.answers() != null && !p.answers().isEmpty()) {
            card.getChildren().add(label("Выбранные ответы:", "-fx-font-weight: bold;"));
            for (SummaryAnswer a : p.answers()) {
                card.getChildren().add(label("Вопрос " + a.questionNumber() + ": " + a.optionText(), null));
            }
        }
        return card;
    }

    // ---------- Голосование участника ----------

    private void onVoteClicked(int optionId) {
        if (current == null || votedQuestions.contains(current.questionId())) {
            return;
        }
        // Блокируем сразу, чтобы двойной клик не отправил два голоса;
        // при ошибке кнопки вернутся в нужное состояние через updateButtonsEnabled
        optionButtons.forEach(b -> b.setDisable(true));
        statusLabel.setText("Отправка голоса...");
        sendJson(new VoteRequest("vote", current.questionId(), optionId, clientId));
    }

    private void handleStatus(StatusMessage m) {
        if (m == null) {
            return;
        }
        boolean voteConfirmed = "vote_ok".equals(m.type());
        boolean alreadyVoted = "already_voted".equals(m.code());

        if ((voteConfirmed || alreadyVoted) && m.questionId() != null) {
            votedQuestions.add(m.questionId());
            if (!host) {
                applyVotedState();
            }
            return;
        }

        if (!host) {
            updateButtonsEnabled();
        }
        statusLabel.setText(m.message() != null ? m.message() : "Ошибка");
    }

    private void updateButtonsEnabled() {
        boolean voted = current != null && votedQuestions.contains(current.questionId());
        optionButtons.forEach(b -> b.setDisable(voted));
    }

    private void applyVotedState() {
        updateButtonsEnabled();
        boolean voted = current != null && votedQuestions.contains(current.questionId());
        statusLabel.setText(voted ? "Ваш голос учтён" : "Выберите вариант ответа");
    }

    // ---------- Завершение ----------

    @Override
    public void stop() {
        running = false;
        if (out != null) {
            out.close();
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Ошибка закрытия сокета: " + e.getMessage());
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    public static void main(String[] args) {
        Application.launch(ClientMain.class, args);
    }
}