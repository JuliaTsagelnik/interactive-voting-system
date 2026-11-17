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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:sqlite:voting.db";

    public static final String OPTION_A = "Вариант А";
    public static final String OPTION_B = "Вариант Б";
    public static final String OPTION_C = "Вариант В";

    private static final List<String> OPTIONS = List.of(OPTION_A, OPTION_B, OPTION_C);

    private static final ConcurrentHashMap<String, Integer> votes = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<PrintWriter> clients = new CopyOnWriteArrayList<>();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        ExecutorService pool = Executors.newCachedThreadPool();

        // Одно соединение на весь сервер: обработчики синхронизируются на нём сами.
        try (Connection db = DriverManager.getConnection(DB_URL);
             ServerSocket serverSocket = new ServerSocket(PORT)) {

            initDatabase(db);
            loadVotes(db);
            System.out.println("База подключена, голоса загружены: " + votes);

            System.out.println("Сервер запущен, порт " + PORT + ". Ожидание подключений...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, votes, clients, db));
            }
        } catch (SQLException e) {
            System.err.println("Ошибка базы данных: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Ошибка сервера: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    private static void initDatabase(Connection db) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS options (
                        name  TEXT PRIMARY KEY,
                        votes INTEGER NOT NULL DEFAULT 0
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS voters (
                        client_id TEXT PRIMARY KEY
                    )""");
        }

        boolean empty;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM options")) {
            empty = rs.next() && rs.getInt(1) == 0;
        }

        if (empty) {
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO options (name, votes) VALUES (?, 0)")) {
                for (String option : OPTIONS) {
                    ps.setString(1, option);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void loadVotes(Connection db) throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, votes FROM options")) {
            while (rs.next()) {
                votes.put(rs.getString("name"), rs.getInt("votes"));
            }
        }
    }

    /**
     * Формирует снимок результатов в фиксированном порядке вариантов
     * и рассылает его всем клиентам из списка. Синхронизация гарантирует,
     * что более старый снимок не будет отправлен позже более нового.
     */
    public static synchronized void broadcastResults() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (String option : OPTIONS) {
            snapshot.put(option, votes.getOrDefault(option, 0));
        }
        String json = gson.toJson(Map.of("results", snapshot));

        for (PrintWriter writer : clients) {
            writer.println(json);
            if (writer.checkError()) {
                clients.remove(writer);
            }
        }
        System.out.println("Рассылка: " + json);
    }
}