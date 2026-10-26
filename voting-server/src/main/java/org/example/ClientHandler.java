package org.example;
// ClientHandler.java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ConcurrentHashMap<String, Integer> votes;

    public ClientHandler(Socket socket, ConcurrentHashMap<String, Integer> votes) {
        this.socket = socket;
        this.votes = votes;
    }

    @Override
    public void run() {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {

            System.out.println("[" + Thread.currentThread().getName() + "] Клиент подключен: "
                    + s.getRemoteSocketAddress());

            String message;
            while ((message = in.readLine()) != null) {
                System.out.println("[" + Thread.currentThread().getName() + "] Получено: " + message);

                votes.merge(ServerMain.OPTION_A, 1, Integer::sum);
                System.out.println("Текущие голоса: " + votes);

                out.println("Сообщение получено");
            }
        } catch (IOException e) {
            System.err.println("Ошибка обработки клиента: " + e.getMessage());
        }
    }
}