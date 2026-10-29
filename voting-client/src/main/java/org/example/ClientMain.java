package org.example;
// ClientApp.java
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class ClientMain extends Application {

    @Override
    public void start(Stage stage) {
        ObservableList<PieChart.Data> data = FXCollections.observableArrayList(
                new PieChart.Data("Вариант А", 10),
                new PieChart.Data("Вариант Б", 20),
                new PieChart.Data("Вариант В", 30)
        );

        PieChart chart = new PieChart(data);
        chart.setTitle("Результаты голосования");
        chart.setLegendVisible(true);

        Button btnA = new Button("Вариант А");
        Button btnB = new Button("Вариант Б");
        Button btnC = new Button("Вариант В");

        HBox buttons = new HBox(10, btnA, btnB, btnC);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setCenter(chart);
        root.setBottom(buttons);

        stage.setTitle("Голосование");
        stage.setScene(new Scene(root, 500, 450));
        stage.show();
    }

    public static void main(String[] args) {
        Application.launch(ClientMain.class, args);
    }
}