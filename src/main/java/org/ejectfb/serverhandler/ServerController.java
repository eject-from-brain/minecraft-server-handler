package org.ejectfb.serverhandler;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerController {
    private int lineCounter = 0;
    private static final int MAX_LINES = 200; // Максимальное количество строк перед очисткой
    private final StringBuilder consoleBuffer = new StringBuilder();
    private volatile boolean consoleUpdateScheduled = false;

    // FXML элементы
    @FXML private TextArea consoleOutput;
    @FXML private TextField serverCommandField;
    @FXML private TextField pollIntervalField;
    @FXML private TextField serverCommandInput;
    @FXML private PasswordField botTokenField;
    @FXML private TextField chatIdField;
    @FXML private Button startStopButton;
    @FXML private Button restartButton;
    @FXML private Button statsButton;
    @FXML private Label lineCounterLabel;

    // Серверные переменные
    private Process serverProcess;
    private BufferedReader processReader;
    private BufferedWriter processWriter;
    private ExecutorService executorService;
    private Timer statsTimer;
    private int pollIntervalHours = 3;
    private TelegramBot telegramBot;

    // Свойства для биндинга
    private final BooleanProperty isServerRunning = new SimpleBooleanProperty(false);
    private final BooleanProperty isManualStop = new SimpleBooleanProperty(false);

    @FXML
    private void testTelegramConnection() {
        String token = botTokenField.getText();
        String chatId = chatIdField.getText();

        if (token.isEmpty() || chatId.isEmpty()) {
            appendToConsole("Ошибка: токен бота и chat ID должны быть заполнены");
            return;
        }

        try {
            telegramBot = new TelegramBot(token, chatId);
            telegramBot.sendMessage("✅ Проверка соединения: бот успешно подключен!");
            appendToConsole("Телеграм бот успешно подключен");
        } catch (Exception e) {
            appendToConsole("Ошибка подключения Telegram бота: " + e.getMessage());
        }
    }

    @FXML
    public void initialize() {
        // Инициализация счетчика строк
        lineCounterLabel.setText("Строк: 0/" + MAX_LINES);

        // Настройка биндингов
        BooleanBinding serverNotRunning = isServerRunning.not();


        restartButton.disableProperty().bind(serverNotRunning);
        statsButton.disableProperty().bind(serverNotRunning);
        serverCommandInput.disableProperty().bind(serverNotRunning);

        startStopButton.textProperty().bind(
                Bindings.when(isServerRunning)
                        .then("Остановить")
                        .otherwise("Запустить")
        );

        loadSettings();
    }

    private void initTelegramBot() {
        String token = botTokenField.getText();
        String chatId = chatIdField.getText();

        if (token.isEmpty() || chatId.isEmpty()) {
            appendToConsole("Предупреждение: токен бота или chat ID не заполнены. Уведомления в Telegram отправляться не будут.");
            return;
        }

        try {
            telegramBot = new TelegramBot(token, chatId);
        } catch (Exception e) {
            appendToConsole("Ошибка инициализации Telegram бота: " + e.getMessage());
        }
    }

    // Обработчики событий
    @FXML
    private void handleStartStop() {
        if (isServerRunning.get()) stopServer();
        else startServer();
    }

    @FXML
    private void handleRestart() {
        if (isServerRunning.get()) sendCommandToServer("stop");
    }

    @FXML
    private void handleSendStats() {
        sendServerStats();
    }

    @FXML
    private void handleApplyInterval() {
        try {
            int newInterval = Integer.parseInt(pollIntervalField.getText());
            if (newInterval > 0) {
                pollIntervalHours = newInterval;
                startStatsTimer();
                saveSettings();
                appendToConsole("Интервал опроса изменен на " + pollIntervalHours + " часов");
            }
        } catch (NumberFormatException e) {
            appendToConsole("Ошибка: введите корректное число часов");
        }
    }

    @FXML
    private void handleConsoleInput(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && isServerRunning.get()) {
            String command = serverCommandInput.getText();
            if (!command.isEmpty()) {
                sendCommandToServer(command);
                serverCommandInput.clear();
            }
        }
    }

    @FXML
    private void handleClearConsole() {
        consoleOutput.clear();
        lineCounter = 0;
        appendToConsole("--- Консоль была очищена вручную ---");
    }

    // Методы работы с сервером
    private void startServer() {
        // Сбрасываем флаг ручной остановки при новом запуске
        isManualStop.set(false);
        // Инициализация Telegram бота в отдельном потоке
        CompletableFuture.runAsync(() -> {
            try {
                initTelegramBot();
            } catch (Exception e) {
                appendToConsole("Ошибка инициализации Telegram бота: " + e.getMessage());
            }
        });

        String command = serverCommandField.getText();
        if (command.isEmpty()) {
            appendToConsole("Ошибка: команда запуска сервера не указана");
            return;
        }

        try {
            appendToConsole("Запуск сервера: " + command);

            // Настройка ProcessBuilder с буферизацией
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command)
                    .redirectErrorStream(true);
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            serverProcess = pb.start();

            // Асинхронное чтение вывода сервера
            executorService = Executors.newSingleThreadExecutor();
            executorService.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String consoleLine = line;
                        // Ограничение частоты обновления UI
                        Platform.runLater(() -> appendToConsole(consoleLine));

                        // Небольшая пауза для снижения нагрузки
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (IOException e) {
                    if (!e.getMessage().contains("Stream closed")) {
                        Platform.runLater(() ->
                                appendToConsole("Ошибка чтения вывода: " + e.getMessage()));
                    }
                }
            });

            // Инициализация писателя для команд
            processWriter = new BufferedWriter(
                    new OutputStreamWriter(serverProcess.getOutputStream(), StandardCharsets.UTF_8));

            isServerRunning.set(true);
            startProcessMonitor();
            startStatsTimer();

            // Асинхронная отправка уведомления в Telegram
            CompletableFuture.runAsync(() -> {
                try {
                    if (telegramBot != null) {
                        telegramBot.sendMessage("✅ Сервер Minecraft запущен");
                    }
                } catch (Exception e) {
                    appendToConsole("Ошибка отправки в Telegram: " + e.getMessage());
                }
            });

        } catch (IOException e) {
            appendToConsole("Ошибка при запуске сервера: " + e.getMessage());
            cleanup();
        }
    }

    private void stopServer() {
        if (!isServerRunning.get()) return;

        isManualStop.set(true); // Помечаем как ручную остановку
        appendToConsole("Остановка сервера...");
        sendCommandToServer("stop");

        try {
            Thread.sleep(5000);
            if (serverProcess.isAlive()) serverProcess.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        cleanup();

        if (telegramBot != null) {
            telegramBot.sendMessage("⛔ Сервер Minecraft остановлен");
        }
    }

    private void cleanup() {
        try {
            if (processReader != null) processReader.close();
            if (processWriter != null) processWriter.close();
            if (executorService != null) executorService.shutdownNow();
            if (statsTimer != null) statsTimer.cancel();
        } catch (IOException e) {
            appendToConsole("Ошибка при очистке ресурсов: " + e.getMessage());
        } finally {
            // Не сбрасываем isManualStop здесь, чтобы сохранить состояние
            isServerRunning.set(false);
        }
    }

    // Вспомогательные методы
    private void readServerOutput() {
        Executors.newSingleThreadExecutor().submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    final String consoleLine = line;
                    Platform.runLater(() -> {
                        appendToConsole(consoleLine);

                        // Ограничиваем частоту обновления UI (не чаще 30 FPS)
                        try {
                            Thread.sleep(33); // ~30 обновлений в секунду
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            } catch (IOException e) {
                if (!e.getMessage().contains("Stream closed")) {
                    Platform.runLater(() ->
                            appendToConsole("Ошибка чтения вывода: " + e.getMessage()));
                }
            }
        });
    }

    private void sendCommandToServer(String command) {
        if (!isServerRunning.get() || processWriter == null) return;

        try {
            processWriter.write(command + "\n");
            processWriter.flush();
            appendToConsole("> " + command);
        } catch (IOException e) {
            appendToConsole("Ошибка отправки команды серверу: " + e.getMessage());
        }
    }

    private void startProcessMonitor() {
        new Thread(() -> {
            while (isServerRunning.get()) {
                try {
                    if (!serverProcess.isAlive()) {
                        Platform.runLater(() -> {
                            appendToConsole("Процесс сервера завершился неожиданно");
                            cleanup();

                            // Перезапускаем только если это не было ручной остановкой
                            if (!isManualStop.get()) {
                                appendToConsole("Попытка перезапуска сервера...");
                                startServer();
                            } else {
                                appendToConsole("Сервер был остановлен вручную. Автоперезапуск не выполняется.");
                            }
                        });
                        break;
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    private void startStatsTimer() {
        if (statsTimer != null) statsTimer.cancel();

        statsTimer = new Timer(true);
        statsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendServerStats();
            }
        }, pollIntervalHours * 3600 * 1000L, pollIntervalHours * 3600 * 1000L);
    }

    private void sendServerStats() {
        if (!isServerRunning.get()) {
            telegramBot.sendMessage("Сервер в данный момент не запущен");
            return;
        }

        String stats = getServerStats();
        telegramBot.sendMessage(stats);
        appendToConsole("Статистика отправлена в Telegram:\n" + stats);
    }

    private String getServerStats() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        return String.format(
                "📊 Статистика сервера Minecraft (%s)\n" +
                        "🔄 Состояние: работает\n" +
                        "🧮 Память: %d/%dMB (Max: %dMB)\n" +
                        "👥 Онлайн: %d игроков\n" +
                        "⏱ TPS: %.1f\n" +
                        "⏳ Время работы: %s",
                dtf.format(LocalDateTime.now()),
                totalMemory - freeMemory, totalMemory, maxMemory,
                getOnlinePlayers(),
                getTPS(),
                getUptime()
        );
    }

    private int getOnlinePlayers() {
        return 0; // TODO: реализовать
    }

    private double getTPS() {
        return 20.0; // TODO: реализовать
    }

    private String getUptime() {
        return "N/A"; // TODO: реализовать
    }

    private void appendToConsole(String text) {
        synchronized (consoleBuffer) {
            consoleBuffer.append(text).append("\n");

            if (!consoleUpdateScheduled) {
                consoleUpdateScheduled = true;
                Platform.runLater(() -> {
                    synchronized (consoleBuffer) {
                        consoleOutput.appendText(consoleBuffer.toString());
                        consoleBuffer.setLength(0);
                        consoleUpdateScheduled = false;

                        // Автоочистка при превышении лимита
                        if (consoleOutput.getLength() > 100_000) {
                            consoleOutput.replaceText(
                                    consoleOutput.getLength() - 50_000,
                                    consoleOutput.getLength(),
                                    "\n--- TRUNCATED ---\n"
                            );
                        }
                    }
                });
            }
        }
    }

    private void loadSettings() {
        serverCommandField.setText("java -Xmx8G -Xms1G -Dfile.encoding=UTF-8 -jar spigot-1.20.1.jar nogui --world-dir=C:\\MinecraftSreverWorlds");
        pollIntervalField.setText(String.valueOf(pollIntervalHours));
    }

    private void saveSettings() {
        // TODO: реализовать
    }
}