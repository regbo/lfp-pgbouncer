package com.lfp.pgbouncer_app.pgbouncer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.lfp.joe.core.function.Muto;
import com.lfp.joe.core.lock.FileLocks;
import com.lfp.joe.serial.Serials;
import com.lfp.joe.utils.Utils;
import com.lfp.joe.utils.function.Requires;
import com.lfp.joe.utils.function.Throwing.ThrowingBiConsumer;

import one.util.streamex.EntryStream;

public class PGBouncerIni {

	private final File pgBouncerIni;
	private final File lockFile;

	protected PGBouncerIni(File pgBouncerIni) {
		this.pgBouncerIni = Objects.requireNonNull(pgBouncerIni);
		this.pgBouncerIni.getParentFile().mkdirs();
		this.lockFile = new File(this.pgBouncerIni.getAbsolutePath() + ".lock");
	}

	public boolean access(
			ThrowingBiConsumer<Supplier<EntryStream<String, String>>, BiConsumer<String, String>, IOException> accessor)
			throws FileNotFoundException, IOException {
		if (accessor == null)
			return false;
		return FileLocks.access(lockFile, true, fc -> {
			Supplier<List<Line>> lineListSupplier = Utils.Functions.memoize(() -> {
				try (var lineStream = Utils.Files.streamLines(pgBouncerIni)) {
					return lineStream.map(Line::new).toList();
				}
			}, null);
			Supplier<EntryStream<String, String>> reader = () -> {
				return Utils.Lots.stream(lineListSupplier.get()).filter(v -> v.getKey().isPresent())
						.mapToEntry(v -> v.getKey().get(), v -> v.getValue().orElse(null));
			};
			var modified = Muto.createBoo();
			BiConsumer<String, String> writer = (key, value) -> {
				Requires.notBlank(key);
				var exists = false;
				for (var line : lineListSupplier.get()) {
					if (key.equals(line.getKey().orElse(null))) {
						exists = true;
						if (line.setValue(value))
							modified.set(true);
					}
				}
				if (!exists && value != null) {
					lineListSupplier.get().add(new Line(key, value));
					modified.set(true);
				}
			};
			accessor.accept(reader, writer);
			if (modified.get())
				try (var bw = Utils.Files.bufferedWriter(pgBouncerIni)) {
					var lineIter = Utils.Lots.stream(lineListSupplier.get()).map(v -> v.line).nonNull().iterator();
					while (lineIter.hasNext()) {
						bw.write(lineIter.next());
						if (lineIter.hasNext())
							bw.newLine();
					}
				}
			return modified.get();
		});
	}

	private static class Line {

		private String line;

		public Line(String line) {
			this.line = Utils.Strings.trim(line);
		}

		public Line(String key, String value) {
			super();
			this.line = Utils.Strings.trim(formatLine(key, value));
		}

		public Optional<String> getKey() {
			return getEntry().map(Entry::getKey);
		}

		public Optional<String> getValue() {
			return getEntry().map(Entry::getValue);
		}

		public boolean setValue(String value) {
			value = Utils.Strings.trim(value);
			boolean result;
			if (value == null) {
				result = line != null;
				line = null;
			} else {
				result = !value.equals(getValue().orElse(null));
				if (result)
					line = formatLine(getKey().get(), value);
			}
			return result;
		}

		protected Optional<Entry<String, String>> getEntry() {
			if (line == null || line.isEmpty())
				return Optional.empty();
			if (line.startsWith("[") && line.endsWith("]"))
				return Optional.empty();
			if (line.startsWith("#"))
				return Optional.empty();
			if (line.startsWith(";"))
				return Optional.empty();
			var splitAt = Utils.Strings.indexOf(line, "=");
			String key;
			String value;
			if (splitAt < 0) {
				key = line;
				value = "";
			} else {
				key = line.substring(0, splitAt);
				value = line.substring(splitAt + 1);
			}
			key = Utils.Strings.trim(key);
			value = Utils.Strings.trim(value);
			if (Utils.Strings.isBlank(key))
				return Optional.empty();
			return Optional.of(Map.entry(key, value));
		}

		private static String formatLine(String key, String value) {
			Requires.notBlank(key);
			return String.format("%s=%s", key, value);
		}

	}

	public static void main(String[] args) throws FileNotFoundException, IOException {
		var pgBouncerIni = new PGBouncerIni(new File("temp/pgbouncer.ini"));
		pgBouncerIni.access((reader, writer) -> {
			var grouping = reader.get().grouping();
			System.out.println(Serials.Gsons.getPretty().toJson(grouping));
			writer.accept("listen_addr", "127.0.0.1");
			writer.accept("poppy", "cock");
		});
	}

}
