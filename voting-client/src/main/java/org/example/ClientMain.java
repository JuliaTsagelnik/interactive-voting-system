// ClientMain.java
package org.example;

import com.google.gson.Gson;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClientMain extends Application {

    private record VoteRequest(String action, String choice, String clientId) {}

    private record ResultsMessage(Map<String, Integer> results) {}

    private static final String HOST = "localhost";
    private static final int PORT = 8080;
    private static final List<String> OPTIONS = List.of("Вариант А", "Вариант Б", "Вариант В");

    private final String clientId = UUID.randomUUID().toString();

    private final Gson gson = new Gson();
    private final Map<String, PieChart.Data> slices = new LinkedHashMap<>();
    private final HBox buttons = new HBox(10);

    private volatile boolean running = true;
    private Socket socket;
    private PrintWriter out;
    private Thread readerThread;

    @Override
    public void start(Stage stage) {
        PieChart chart = new PieChart(FXCollections.observableArrayList());
        chart.setTitle("Результаты голосования");
        chart.setLegendVisible(true);

        for (String option : OPTIONS) {
            PieChart.Data slice = new PieChart.Data(option, 0);
            slices.put(option, slice);
            chart.getData().add(slice);

            Button button = new Button(option);
            button.setOnAction(e -> sendVote(option));
            buttons.getChildren().add(button);
        }
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setCenter(chart);
        root.setBottom(buttons);

        stage.setTitle("Голосование");
        stage.setScene(new Scene(root, 500, 450));
        stage.show();

        connect();
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
        } catch (IOException e) {
            buttons.setDisable(true);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Не удалось подключиться к " + HOST + ":" + PORT + "\n" + e.getMessage());
            alert.setHeaderText("Сервер недоступен");
            alert.show();
        }
    }

    private void sendVote(String choice) {
        if (out == null) {
            return;
        }
        out.println(gson.toJson(new VoteRequest("vote", choice, clientId)));
        if (out.checkError()) {
            buttons.setDisable(true);
        }
    }

    private void readLoop(BufferedReader in) {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                ResultsMessage message;
                try {
                    message = gson.fromJson(line, ResultsMessage.class);
                } catch (JsonSyntaxException e) {
                    System.err.println("Некорректный JSON от сервера: " + e.getMessage());
                    continue;
                }
                if (message != null && message.results() != null) {
                    Map<String, Integer> results = message.results();
                    Platform.runLater(() -> updateChart(results));
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Ошибка чтения: " + e.getMessage());
            }
        } finally {
            if (running) {
                Platform.runLater(() -> buttons.setDisable(true));
            }
        }
    }

    private void updateChart(Map<String, Integer> results) {
        results.forEach((option, count) -> {
            PieChart.Data slice = slices.get(option);
            if (slice != null && count != null) {
                slice.setPieValue(count);
            }
        });
    }

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