package com.lfp.pgbouncer_app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

import com.lfp.joe.core.classpath.Instances;
import com.lfp.joe.core.config.MachineConfig;
import com.lfp.joe.utils.Utils;

import one.util.streamex.EntryStream;

public class ENVService {

	public static void init() {
		Instances.get(ENVService.class);
	}

	private static final Class<?> THIS_CLASS = new Object() {
	}.getClass().getEnclosingClass();
	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(THIS_CLASS);

	private ENVService() {
		var map = Utils.Machine.streamEnvironmentVariables("_", "_FILE").toMap();
		if (MachineConfig.isDeveloper()) {
			var configPrivate = new File("src/main/resources/config-private");
			if (configPrivate.exists()) {
				for (var file : configPrivate.listFiles()) {
					var name = file.getName();
					var splitAt = Utils.Strings.lastIndexOf(name, ".");
					String ext;
					if (splitAt < 0)
						ext = "";
					else {
						ext = name.substring(splitAt + 1);
						name = name.substring(0, splitAt);
					}
					if (Utils.Strings.equalsIgnoreCase(name, "env") || Utils.Strings.equalsIgnoreCase(ext, "env")) {
						EntryStream<String, String> append;
						try {
							append = Utils.Machine.streamEnvironmentVariables(new FileInputStream(file), "_", "_FILE")
									.flatMapValues(Collection::stream);
						} catch (IOException e) {
							throw RuntimeException.class.isInstance(e) ? RuntimeException.class.cast(e)
									: new RuntimeException(e);
						}
						map = Utils.Lots.streamMultimap(map).append(append).grouping();
					}
				}

			}
			//logger.info("envMap:\n{}", Serials.Gsons.getPretty().toJson(map));
		}
		for (var ent : map.entrySet()) {
			var name = ent.getKey();
			var value = Utils.Lots.stream(ent.getValue()).filter(Utils.Strings::isNotBlank).findFirst().orElse("");
			var currentValue = System.getenv(name);
			if (Objects.equals(value, currentValue))
				continue;
			Utils.Machine.setEnvironmentVariable(name, value);
		}
	}

}
