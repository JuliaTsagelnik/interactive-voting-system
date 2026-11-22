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
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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

    private record HelloRequest(String action, String role, String clientId) {}
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

    private static final String HOST = "localhost";
    private static final int PORT = 8080;

    private final boolean hostMode;
    private final String clientId = UUID.randomUUID().toString();
    private final Gson gson = new Gson();

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

    // Общие элементы интерфейса
    private final Label waitingLabel = new Label();
    private final Label themeLabel = new Label();
    private final Label pollLabel = new Label();
    private final Label counterLabel = new Label();
    private final Label questionLabel = new Label();
    private final Label statusLabel = new Label();
    private final PieChart chart = new PieChart();
    private final VBox pollPane = new VBox(10);

    // Элементы ведущего
    private final ListView<CatalogTheme> themesList = new ListView<>();
    private final ListView<CatalogPoll> pollsList = new ListView<>();
    private final Button openBtn = new Button("Открыть опрос");
    private final Button prevBtn = new Button("← Предыдущий");
    private final Button nextBtn = new Button("Следующий →");
    private final Button closeBtn = new Button("Закрыть опрос");
    private final VBox resultsBox = new VBox(4);

    // Элементы участника
    private final VBox answerBox = new VBox(8);

    public ClientMain() {
        // Режим берётся из аргументов: Application.launch передаёт их в Parameters
        this.hostMode = false;
    }

    private boolean isHost() {
        return getParameters().getRaw().contains("--host");
    }

    @Override
    public void start(Stage stage) {
        boolean host = isHost();

        buildCommonUi();

        Region content;
        if (host) {
            content = buildHostLayout();
            stage.setTitle("Голосование — Ведущий");
        } else {
            content = buildParticipantLayout();
            stage.setTitle("Голосование — Участник");
        }

        stage.setMinWidth(850);
        stage.setMinHeight(650);
        stage.setScene(new Scene(content, 950, 700));
        stage.show();

        showWaiting(host);
        connect(host);
    }

    // ---------- Построение интерфейса ----------

    private void buildCommonUi() {
        waitingLabel.setWrapText(true);
        waitingLabel.setMaxWidth(600);
        waitingLabel.setStyle("-fx-font-size: 22px; -fx-text-fill: #666666;");
        waitingLabel.setAlignment(Pos.CENTER);
        waitingLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        themeLabel.setWrapText(true);
        themeLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #777777;");
        pollLabel.setWrapText(true);
        pollLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        counterLabel.setWrapText(true);
        counterLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #444444;");
        questionLabel.setWrapText(true);
        questionLabel.setStyle("-fx-font-size: 19px; -fx-font-weight: bold;");

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 14px;");

        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMinHeight(220);
        VBox.setVgrow(chart, Priority.ALWAYS);

        pollPane.setPadding(new Insets(15));
        pollPane.getChildren().addAll(themeLabel, pollLabel, counterLabel, questionLabel, chart);
    }

    private Region buildHostLayout() {
        enableWrap(themesList);
        enableWrap(pollsList);

        themesList.getSelectionModel().selectedItemProperty().addListener((obs, old, theme) -> {
            pollsList.setItems(theme == null
                    ? FXCollections.observableArrayList()
                    : FXCollections.observableArrayList(theme.polls()));
        });
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
        VBox left = new VBox(8,
                new Label("Темы"), themesList,
                new Label("Опросы выбранной темы"), pollsList,
                openBtn);
        left.setPadding(new Insets(15));
        left.setPrefWidth(320);
        left.setMinWidth(280);

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
        closeBtn.setOnAction(e -> sendJson(new ActionRequest("host_close_poll")));

        HBox controls = new HBox(10, prevBtn, nextBtn, closeBtn);
        controls.setAlignment(Pos.CENTER);

        resultsBox.setPadding(new Insets(4, 0, 4, 0));
        pollPane.getChildren().addAll(resultsBox, controls);

        BorderPane root = new BorderPane();
        root.setLeft(left);
        root.setCenter(buildRightArea());
        return root;
    }

    private Region buildParticipantLayout() {
        answerBox.setFillWidth(true);
        pollPane.getChildren().add(answerBox);

        BorderPane root = new BorderPane();
        root.setCenter(buildRightArea());
        return root;
    }

    /** Область справа: экран ожидания и панель опроса друг над другом, ниже строка статуса. */
    private Region buildRightArea() {
        StackPane stack = new StackPane(waitingLabel, pollPane);
        VBox.setVgrow(stack, Priority.ALWAYS);
        VBox area = new VBox(stack, statusLabel);
        area.setPadding(new Insets(0, 15, 12, 0));
        statusLabel.setPadding(new Insets(0, 0, 0, 15));
        return area;
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

    // ---------- Сеть ----------

    private boolean connected() {
        return out != null && socket != null && !socket.isClosed();
    }

    private void connect(boolean host) {
        try {
            socket = new Socket(HOST, PORT);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            readerThread = new Thread(() -> readLoop(in), "server-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            sendJson(new HelloRequest("hello", host ? "host" : "client", clientId));
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
                    if (isHost()) {
                        showCatalog(gson.fromJson(obj, CatalogMessage.class));
                    }
                }
                case "waiting" -> showWaiting(isHost());
                case "active_question" -> showActive(gson.fromJson(obj, ActiveQuestion.class));
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

    private void showWaiting(boolean host) {
        current = null;
        pieData.clear();
        chart.getData().clear();
        optionButtons.clear();
        answerBox.getChildren().clear();
        resultsBox.getChildren().clear();

        pollPane.setVisible(false);
        waitingLabel.setText(host
                ? "Выберите тему и опрос слева, затем нажмите «Открыть опрос»."
                : "Ожидание выбора опроса ведущим...");
        waitingLabel.setVisible(true);
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

        waitingLabel.setVisible(false);
        pollPane.setVisible(true);

        themeLabel.setText(q.theme());
        pollLabel.setText(q.pollTitle());
        counterLabel.setText("Вопрос " + (q.questionIndex() + 1) + " из " + q.totalQuestions());
        questionLabel.setText(q.text());

        if (sameQuestion) {
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
            if (!isHost()) {
                rebuildAnswerButtons(q);
                statusLabel.setText("");
            }
        }

        if (isHost()) {
            rebuildResults(q);
            prevBtn.setDisable(q.questionIndex() <= 0);
            nextBtn.setDisable(q.questionIndex() >= q.totalQuestions() - 1);
            closeBtn.setDisable(false);
        } else {
            applyVotedState();
        }
    }

    private void rebuildResults(ActiveQuestion q) {
        resultsBox.getChildren().clear();
        int total = 0;
        for (int i = 0; i < q.options().size(); i++) {
            OptionResult option = q.options().get(i);
            total += option.votes();
            Label label = new Label((i + 1) + ". " + option.text() + " — " + option.votes());
            label.setWrapText(true);
            resultsBox.getChildren().add(label);
        }
        Label totalLabel = new Label("Всего голосов: " + total);
        totalLabel.setStyle("-fx-text-fill: #777777;");
        resultsBox.getChildren().add(totalLabel);
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
            button.setStyle("-fx-font-size: 14px; -fx-alignment: CENTER_LEFT; -fx-padding: 8 12 8 12;");
            button.setOnAction(e -> onVoteClicked(option.id()));
            optionButtons.add(button);
            answerBox.getChildren().add(button);
        }
    }

    // ---------- Голосование участника ----------

    private void onVoteClicked(int optionId) {
        if (current == null || votedQuestions.contains(current.questionId())) {
            return;
        }
        // Блокируем сразу, чтобы двойной клик не отправил два голоса;
        // при ошибке кнопки вернутся в нужное состояние через applyVotedState
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
            if (!isHost()) {
                applyVotedState();
            }
            return;
        }

        // Остальные ошибки: возвращаем кнопки в корректное состояние и показываем причину
        if (!isHost()) {
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