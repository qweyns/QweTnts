package ru.qweyns.qwetnts.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/** I/O-хелперы: атомарная запись и создание файлов. */
public final class Io {

    private Io() {
    }

    /**
     * Записывает {@code bytes} во временный файл рядом с {@code target} и
     * атомарно переименовывает его через {@link Files#move} с ATOMIC_MOVE.
     * Это защищает от получения 0-байтового файла при крахе сервера/диска.
     */
    public static void writeAtomic(@NotNull Path target, byte[] bytes,
                                   @NotNull Logger log) {
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Path tmp = parent.resolve(target.getFileName().toString() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // ФС не поддерживает atomic move — падаем до REPLACE_EXISTING.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "Не удалось атомарно записать " + target, ex);
        }
    }

    public static void writeAtomic(@NotNull Path target, @NotNull String text,
                                   @NotNull Logger log) {
        writeAtomic(target, text.getBytes(StandardCharsets.UTF_8), log);
    }
}
