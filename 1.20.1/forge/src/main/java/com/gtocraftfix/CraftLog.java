package com.gtocraftfix;

import net.minecraftforge.fml.loading.FMLPaths;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 {@code gtocraftfix} 這個 logger 的輸出導到自己的檔案 {@code logs/craftfix.log}。
 *
 * <p><b>為什麼要獨立</b>：本 mod 的帳本／探針／凍結報告在忙碌的網路上每天可以寫出數十萬行，
 * 混在 {@code latest.log} 裡會把它撐到數十 MB（實測 2026-08-22 單一場就 31 MB），
 * 別的 mod 的訊息被淹掉，要查 craftfix 自己的東西也得先 grep 一輪。
 *
 * <p><b>預設完全獨立</b>（{@code additivity=false}）：craftfix 的行**不再**進 {@code latest.log}。
 * 要兩邊都寫就加 {@code -Dgtodiag.logToMain=true}。
 *
 * <p>輪替是自己做的（開服時把上一場的 {@code craftfix.log} 改名保留，最多留
 * {@code -Dgtodiag.logKeep} 份，預設 5），不依賴 log4j 的 RollingFileAppender——
 * 那組 builder 的方法名在 log4j 各版之間改過，寫死會變成跨版編譯風險。
 *
 * <p>整段以 try/catch 包住：安裝失敗只印一行警告並繼續用主 log，不影響 mod 功能。
 */
public final class CraftLog {

    private static final String LOGGER_NAME = "gtocraftfix";
    private static final String APPENDER_NAME = "gtocraftfix-file";
    private static boolean installed;

    private CraftLog() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        var log = LogManager.getLogger(LOGGER_NAME);
        try {
            boolean alsoMain = Boolean.getBoolean("gtodiag.logToMain");
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("craftfix.log");
            rotate(dir, file);

            var ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();

            var layout = PatternLayout.newBuilder()
                    .withConfiguration(config)
                    .withCharset(StandardCharsets.UTF_8)
                    .withPattern("[%d{yyyy-MM-dd HH:mm:ss.SSS}] [%t/%level] %msg%n")
                    .build();

            // 一律用 with* 不用 set*：set* 是 log4j 2.17.2 之後才補的別名，Forge 47 編譯期帶的
            // log4j-core 還沒有，用了會 cannot find symbol。with* 新舊版都在（新版只是 deprecated）。
            // 也不鏈式呼叫：FileAppender.newBuilder() 的自我型別參數（B extends Builder<B>）在鏈式
            // 推導下會讓繼承來的 setName/setLayout 解析不到；宣告成具體變數逐句設定最穩。
            FileAppender.Builder<?> builder = FileAppender.newBuilder();
            builder.withName(APPENDER_NAME);
            builder.withLayout(layout);
            builder.withConfiguration(config);
            builder.withIgnoreExceptions(true);
            builder.withFileName(file.toString());
            builder.withAppend(false);
            // [3.15.0] 預設立即 flush：3.14.0 用緩衝，最後一個 buffer 沒落地，現場查 log
            // 永遠看不到最新那幾行。要拿回緩衝用 -Dgtodiag.logBuffered=true。
            builder.withImmediateFlush(!Boolean.getBoolean("gtodiag.logBuffered"));
            Appender appender = builder.build();
            appender.start();
            config.addAppender(appender);

            LoggerConfig lc = config.getLoggerConfig(LOGGER_NAME);
            if (!LOGGER_NAME.equals(lc.getName())) {
                // 還沒有專屬 LoggerConfig（繼承 root）→ 自己建一個，才控制得了 additivity
                lc = new LoggerConfig(LOGGER_NAME, Level.ALL, alsoMain);
                config.addLogger(LOGGER_NAME, lc);
            }
            lc.addAppender(appender, null, null);
            lc.setAdditive(alsoMain);
            ctx.updateLoggers();

            log.info("[craftfix] 診斷 log 已獨立到 {}{}", file,
                    alsoMain ? "（同時保留在 latest.log）" : "（不再寫入 latest.log；要兩邊都寫加 -Dgtodiag.logToMain=true）");
        } catch (Throwable t) {
            log.warn("[craftfix] 獨立 log 檔安裝失敗，維持寫入主 log：{}", t.toString());
        }
    }

    /** 開服時把上一場的 craftfix.log 改名保留，並把份數修剪到上限。 */
    private static void rotate(Path dir, Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) {
            return;
        }
        // 用上一場最後修改時間當檔名，不用 now()：檔名才對得上那一場的內容
        var stamp = java.time.LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(file).toInstant(), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path archived = dir.resolve("craftfix-" + stamp + ".log");
        for (int i = 1; Files.exists(archived) && i < 100; i++) {
            archived = dir.resolve("craftfix-" + stamp + "-" + i + ".log");
        }
        Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING);

        int keep = Math.max(0, Integer.getInteger("gtodiag.logKeep", 5));
        List<Path> old = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("craftfix-") && n.endsWith(".log");
            }).forEach(old::add);
        }
        if (old.size() <= keep) {
            return;
        }
        old.sort(Comparator.comparing((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());
        for (int i = keep; i < old.size(); i++) {
            try {
                Files.deleteIfExists(old.get(i));
            } catch (IOException ignored) {
                // 刪不掉就留著，不值得為了修剪舊 log 影響啟動
            }
        }
    }
}
