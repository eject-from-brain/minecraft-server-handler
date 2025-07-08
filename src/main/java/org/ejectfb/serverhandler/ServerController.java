package org.ejectfb.serverhandler;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.ejectfb.serverhandler.services.ServerDataService;
import org.ejectfb.serverhandler.services.TelegramBotService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerController {
    private int lineCounter = 0;
    private long serverStartTime = 0;
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
    private TelegramBotService telegramBot;

    // Свойства для биндинга
    private final BooleanProperty isServerRunning = new SimpleBooleanProperty(false);
    private final BooleanProperty isManualStop = new SimpleBooleanProperty(false);
    private volatile ServerDataService currentStatsData;
    private ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor();

    @FXML
    private void testTelegramConnection() {
        String token = botTokenField.getText();
        String chatId = chatIdField.getText();

        if (token.isEmpty() || chatId.isEmpty()) {
            appendToConsole("Ошибка: токен бота и chat ID должны быть заполнены");
            telegramBot = null;
            return;
        }

        try {
            telegramBot = new TelegramBotService(token, chatId);
            if (telegramBot.isBotConnected()) appendToConsole("Телеграм бот успешно подключен");
        } catch (Exception e) {
            appendToConsole("Ошибка подключения Telegram бота: " + e.getMessage());
            telegramBot = null;
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
            telegramBot = new TelegramBotService(token, chatId);
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
        requestStats();
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
                // Прокрутка вниз после ввода команды
                Platform.runLater(() -> {
                    consoleOutput.setScrollTop(Double.MAX_VALUE);
                });
            }
        }
    }

    @FXML
    private void handleClearConsole() {
        consoleOutput.clear();
        lineCounter = 0;
        lineCounterLabel.setText("Строк: " + lineCounter + "/" + MAX_LINES);
        appendToConsole("--- Консоль была очищена вручную ---");
    }

    private void startServer() {
        serverStartTime = System.currentTimeMillis();
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
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            }

            pb.redirectErrorStream(true);
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

        isManualStop.set(true);
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
                requestStats();
            }
        }, pollIntervalHours * 3600 * 1000L, pollIntervalHours * 3600 * 1000L);
    }

    private void sendServerStats() {
        if (!isServerRunning.get()) {
            appendToConsole("Ошибка: сервер в данный момент не запущен");
            return;
        }

        if (telegramBot == null) {
            appendToConsole("Ошибка: Telegram бот не настроен. Пожалуйста, укажите токен бота и chat ID во вкладке настроек.");
            return;
        }

        String stats = getServerStats();
        try {
            telegramBot.sendMessage(stats);
            appendToConsole("Статистика отправлена в Telegram:\n" + stats);
        } catch (Exception e) {
            appendToConsole("Ошибка отправки статистики в Telegram: " + e.getMessage());
        }
    }

    private String getServerStats() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return String.format(
                """
                        📊 Статистика сервера Minecraft (%s)
                        🔄 Состояние: работает
                        🧮 Память: %s
                        👥 Онлайн: %s игроков
                        ⏱ TPS: %s
                        ⏳ Время работы: %s""",
                dtf.format(LocalDateTime.now()),
                currentStatsData.getMemory(),
                currentStatsData.getOnlinePlayers(),
                currentStatsData.getTps(),
                getUptime()
        );
    }

    private String parseOnlinePlayers() {
        try {
            // Анализируем последние строки консоли
            String consoleText = consoleOutput.getText();
            String[] lines = consoleText.split("\n");

            // Ищем строку с информацией об игроках
            String matchPhrase = "[Server thread/INFO]: There are ";
            for (int i = lines.length - 1; i >= 0; i--) { // Пример строки: "There are 2/20 players online:"
                if (lines[i].contains(matchPhrase)) {
                    String result = lines[i].substring(lines[i].indexOf(matchPhrase) + matchPhrase.length());
                    return result.substring(0, result.indexOf(" "));
                }
            }
        } catch (Exception e) {
            appendToConsole("Ошибка при получении онлайн-игроков: " + e.getMessage());
        }

        return "Неизвестно количество";
    }
    private String parseMemory() {
        try {
            // Анализируем последние строки консоли
            String consoleText = consoleOutput.getText();
            String[] lines = consoleText.split("\n");

            // Ищем строку с информацией об игроках
            String matchPhrase = "Current Memory Usage: ";
            String matchMbPhrase = "mb";
            for (int i = lines.length - 1; i >= 0; i--) { // Пример строки: "There are 2/20 players online:"
                if (lines[i].contains(matchPhrase)) {
                    return lines[i].substring(lines[i].indexOf(matchPhrase) + matchPhrase.length());
                }
            }
        } catch (Exception e) {
            appendToConsole("Ошибка при получении онлайн-игроков: " + e.getMessage());
        }

        return "Неизвестно количество";
    }

    private String parseTPS() {
        if (!isServerRunning.get()) return "Сервер не запущен";

        try {
            // Анализируем последние строки консоли
            String consoleText = consoleOutput.getText();
            String[] lines = consoleText.split("\n");

            // Ищем строку с TPS (последнюю строку с "TPS")
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].contains("TPS")) {
                    // Пример строки: "TPS from last 1m, 5m, 15m: 19.99, 20.00, 20.00"
                    String tpsLine = lines[i];

                    // Извлекаем последнее значение TPS (15 минут)
                    String[] parts = tpsLine.split(":");
                    if (parts.length > 1) {
                        String[] tpsValues = parts[4].trim().split(",");
                        if (tpsValues.length >= 3) {
                            return tpsValues[0].trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            appendToConsole("Ошибка при получении TPS: " + e.getMessage());
        }

        return "Неизвестно...";
    }

    private String getUptime() {
        if (!isServerRunning.get() || serverStartTime == 0) {
            return "N/A";
        }

        long uptimeMillis = System.currentTimeMillis() - serverStartTime;
        return formatDuration(uptimeMillis);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        if (days > 0) {
            return String.format("%dд %dч %dм %dс", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%dч %dм %dс", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dм %dс", minutes, seconds);
        } else {
            return String.format("%dс", seconds);
        }
    }

    private void appendToConsole(String text) {
        synchronized (consoleBuffer) {
            consoleBuffer.append(text).append("\n");
            lineCounter++;
            Platform.runLater(() -> lineCounterLabel.setText("Строк: " + Math.min(lineCounter, 200) + "/" + MAX_LINES));

            if (!consoleUpdateScheduled) {
                consoleUpdateScheduled = true;
                Platform.runLater(() -> {
                    synchronized (consoleBuffer) {
                        // Сохраняем текущую позицию скролла
                        double scrollPosition = consoleOutput.getScrollTop();

                        consoleOutput.appendText(consoleBuffer.toString());
                        consoleBuffer.setLength(0);
                        consoleUpdateScheduled = false;

                        String[] lines = consoleOutput.getText().split("\n");
                        if (lines.length > MAX_LINES) {
                            String trimmedText = String.join("\n",
                                    Arrays.copyOfRange(lines, lines.length - MAX_LINES, lines.length));
                            consoleOutput.setText(trimmedText);
                            lineCounter = MAX_LINES;
                            lineCounterLabel.setText("Строк: " + lineCounter + "/" + MAX_LINES);
                        }

                        // Автоматическая прокрутка вниз
                        consoleOutput.setScrollTop(Double.MAX_VALUE);
                    }
                });
            }
        }
    }

    private void loadSettings() {
        serverCommandField.setText("java -Xmx8G -Xms1G -Dfile.encoding=UTF-8 -jar spigot-1.20.1.jar nogui --world-dir=./worlds");
        pollIntervalField.setText(String.valueOf(pollIntervalHours));
    }

    private void saveSettings() {
        // TODO: реализовать
    }

    private void requestStats() {
        appendToConsole("Запрос статистики сервера...");
        sendCommandToServer("list");
        sendCommandToServer("tps");

        // Запускаем 5-секундный таймер перед сбором статистики
        statsScheduler.schedule(() -> {
            Platform.runLater(() -> {
                appendToConsole("Сбор данных статистики...");
                collectStatsData();
            });
        }, 5, TimeUnit.SECONDS);
    }

    private void collectStatsData() {
        currentStatsData = new ServerDataService();
        currentStatsData.setTps(parseTPS());
        currentStatsData.setOnlinePlayers(parseOnlinePlayers());
        currentStatsData.setMemory(parseMemory());

        if (currentStatsData.isComplete()) sendServerStats();
    }
}