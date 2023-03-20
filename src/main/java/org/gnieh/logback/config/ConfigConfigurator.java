/*
 * This file is part of the logback-config project.
 * Copyright (c) 2018 Lucas Satabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnieh.logback.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.joran.util.ConfigurationWatchListUtil;
import ch.qos.logback.core.joran.util.beans.BeanDescriptionCache;
import ch.qos.logback.core.rolling.RollingPolicy;
import ch.qos.logback.core.spi.ConfigurationEvent;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static ch.qos.logback.classic.spi.Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
import static ch.qos.logback.classic.spi.Configurator.ExecutionStatus.NEUTRAL;

/**
 * A configurator using Typesafe's config library to lookup and load logger
 * configuration.
 *
 * @author Lucas Satabin
 */
public class ConfigConfigurator extends ContextAwareBase implements Configurator {

    @Override
    public ExecutionStatus configure(LoggerContext loggerContext) {

        this.setContext(loggerContext);

        final var beanCache = new BeanDescriptionCache(loggerContext);

        final var loader = getLoader();
        final Config config;
        try {
            config = loader.load();
        } catch (Throwable t) {
            addError("Unable to load Typesafe config", t);
            return NEUTRAL;
        }

        // get the logback configuration root
        final var logbackConfigRoot = config.getString("logback-root");
        final var logbackConfig = config.getConfig(logbackConfigRoot);
        final var appenderConfigs = logbackConfig.getConfig("appenders");

        final var appendersCache = new ConfigAppendersCache();
        appendersCache.setLoader(name -> configureAppender(loggerContext, name, appenderConfigs.getConfig("\"" + name + "\""), beanCache, appendersCache));

        final Map<String, Appender<ILoggingEvent>> appenders = new HashMap<>();
        appenderConfigs.root().forEach((key, value) -> {
            if (value instanceof ConfigObject) {
                try {
                    appenders.put(key, appendersCache.getAppender(key));
                } catch (Exception e) {
                    addError(String.format("Unable to configure appender %s.", key), e);
                }
            } else {
                addWarn(String.format("Invalid appender configuration %s. Ignoring it.", key));
            }
        });

        if (logbackConfig.hasPath("root")) {
            if (logbackConfig.getValue("root") instanceof ConfigObject configObject) {
                configureLogger(loggerContext, appenders, Logger.ROOT_LOGGER_NAME, configObject.toConfig());
            } else {
                addWarn("Invalid ROOT logger configuration. Ignoring it.");
            }
        }

        final var loggerConfigs = logbackConfig.getConfig("loggers");
        loggerConfigs.root().forEach((key, value) -> {
            if (value instanceof ConfigObject configObject) {
                configureLogger(loggerContext, appenders, key, configObject.toConfig());
            } else {
                addWarn(String.format("Invalid logger configuration %s. Ignoring it.", key));
            }
        });

        // Use a LinkedHashSet so order is preserved. We use the first one in as the 'main' URL, under the assumption
        // that maybe that matters somehow to logback. The way we traverse the config tree means that the first one we
        // add will be one that is closer to the root of the tree.
        if (registerFileWatchers(loggerContext, getSourceFiles(config.root(), new LinkedHashSet<>()))) {
            createChangeTask(loggerContext, logbackConfig);
        }

        return loader.allowOtherLoaders() ? NEUTRAL : DO_NOT_INVOKE_NEXT_IF_ANY;
    }

    /**
     * Get the correct config factory object.
     *
     * @return the config factory
     */
    private ConfigLoader getLoader() {
        final var loaders = ServiceLoader.load(ConfigLoader.class).iterator();
        if (loaders.hasNext()) {
            return loaders.next();
        }
        return this::defaultLoader;
    }

    /**
     * Default config loading method.
     *
     * @return the basic TS-config loading
     */
    private Config defaultLoader() {
        return ConfigFactory.load();
    }

    private Appender<ILoggingEvent> configureAppender(LoggerContext loggerContext, String name, Config config,
                                                      BeanDescriptionCache beanCache, ConfigAppendersCache appendersCache) throws ReflectiveOperationException {
        final List<Object> children = new ArrayList<>();

        @SuppressWarnings("unchecked")
        final var clazz = (Class<Appender<ILoggingEvent>>) Class.forName(config.getString("class"));

        final var appender = this.configureObject(loggerContext, clazz, config, children, beanCache, appendersCache);
        appender.setName(name);

        children.forEach(child -> {
            if (child instanceof RollingPolicy rollingPolicy) {
                rollingPolicy.setParent((FileAppender<?>) appender);
            }
            if (child instanceof LifeCycle lifeCycle) {
                lifeCycle.start();
            }
        });

        appender.start();
        return appender;

    }

    /**
     * Configure an object of a given class.
     *
     * @param loggerContext  the context to assign to this object if it is
     *                       {@link ContextAwareBase}
     * @param clazz          the class to instantiate
     * @param config         a configuration containing the object's properties - each
     *                       top-level key except for "class" must have a corresponding setter
     *                       method, or an adder method in the case of lists
     * @param children       a list which, if not null, will be filled with any child objects
     *                       assigned as properties
     * @param beanCache      the bean cache instance
     * @param appendersCache the cache of references to other appenders
     * @return the object instantiated with all properties assigned
     * @throws ReflectiveOperationException if any setter/adder method is missing or if the class cannot be
     *                                      instantiated with a no-argument constructor
     */
    private <T> T configureObject(LoggerContext loggerContext, Class<T> clazz, Config config, List<Object> children,
                                  BeanDescriptionCache beanCache, ConfigAppendersCache appendersCache) throws ReflectiveOperationException {
        final var ctor = clazz.getConstructor();
        final var object = ctor.newInstance();

        if (object instanceof ContextAwareBase contextAware) {
            contextAware.setContext(loggerContext);
        }

        final var propertySetter = new ConfigPropertySetter(beanCache, object);
        propertySetter.setContext(loggerContext);

        // file property (if any) must be set before any other property for appenders
        if (config.hasPath("file")) {
            propertySetter.setProperty("file", config, loggerContext, appendersCache);
        }

        config.withoutPath("class").withoutPath("file").root().forEach((key, value) -> {
            if (value instanceof ConfigObject configObject) {
                final var subConfig = configObject.toConfig();

                if (subConfig.hasPath("class")) {
                    try {
                        final var childClass = Class.forName(subConfig.getString("class"));
                        final var child = this.configureObject(loggerContext, childClass, subConfig, null, beanCache, appendersCache);

                        propertySetter.setRawProperty(NameUtils.toLowerCamelCase(key), child);
                        if (children != null) {
                            children.add(child);
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Unable to configure object", e);
                    }
                } else {
                    propertySetter.setProperty(key, config, loggerContext, appendersCache);
                }
            } else {
                propertySetter.setProperty(key, config, loggerContext, appendersCache);
            }
        });

        return object;
    }

    private void configureLogger(LoggerContext loggerContext, Map<String, Appender<ILoggingEvent>> appenders, String name, Config config) {
        final var logger = loggerContext.getLogger(name);
        final var isRoot = Logger.ROOT_LOGGER_NAME.equals(logger.getName());

        if (config.hasPathOrNull("level")) {
            if (!config.getIsNull("level")) {
                final var levelName = config.getString("level");

                if (!levelName.equalsIgnoreCase("NULL") && !levelName.equalsIgnoreCase("INHERITED")) {
                    logger.setLevel(Level.toLevel(config.getString("level")));
                } else if (isRoot) {
                    addWarn(String.format("Log level %s is not authorized for ROOT logger.", levelName.toUpperCase()));
                }
            } else if (isRoot) {
                addWarn("Log level NULL is not authorized for ROOT logger");
            }
        }

        if (config.hasPath("additivity")) {
            logger.setAdditive(config.getBoolean("additivity"));
        }

        if (config.hasPath("appenders")) {
            config.getStringList("appenders").forEach(appenderRef -> {
                if (appenders.containsKey(appenderRef)) {
                    logger.addAppender(appenders.get(appenderRef));
                } else {
                    addWarn(String.format("Unknown appender %s. Ignoring it.", appenderRef));
                }
            });
        }

    }

    /**
     * Find all real source files in the config. This does not include those encapsulated in jars, etc. Only those that
     * are directly in the file system.
     *
     * @param config the TS-config
     * @param files  the set to add newly found files to
     *
     * @return the set of files found, as URLs
     */
    private Set<URL> getSourceFiles(ConfigValue config, LinkedHashSet<URL> files) {
        if (config.origin().filename() != null) {
            files.add(config.origin().url());
        }
        if (config instanceof ConfigObject configObject) {
            configObject.values().forEach(value -> getSourceFiles(value, files));
        }
        return files;
    }

    /**
     * Register all real config files to be watched by logback.
     *
     * @param loggerContext the logger context
     * @param sourceFiles   the source files to watch
     *
     * @return true if there were any files to watch
     */
    private boolean registerFileWatchers(LoggerContext loggerContext, Set<URL> sourceFiles) {
        final var iterator = sourceFiles.iterator();
        if (iterator.hasNext()) {
            ConfigurationWatchListUtil.setMainWatchURL(loggerContext, iterator.next());
            iterator.forEachRemaining(u -> {
                if (u != null) {
                    ConfigurationWatchListUtil.addToWatchList(loggerContext, u);
                }
            });
            return true;
        }
        addInfo("No configuration files to watch, so no file scanning is possible");
        return false;
    }

    /**
     * Create and schedule the task to check for changes to the watch list.
     *
     * @param loggerContext the logger context
     * @param config        the logback TS-config
     */
    private void createChangeTask(LoggerContext loggerContext, Config config) {
        if (config.hasPath("scan-period") && !config.getIsNull("scan-period")) {
            long delay = config.getDuration("scan-period", TimeUnit.MILLISECONDS);
            if (delay > 0) {
                final Runnable rocTask = () -> {
                    final var configurationWatchList = ConfigurationWatchListUtil.getConfigurationWatchList(loggerContext);
                    if (configurationWatchList == null) {
                        addWarn("Null ConfigurationWatchList in context");
                        return;
                    }
                    final var filesToWatch = configurationWatchList.getCopyOfFileWatchList();
                    if (filesToWatch == null || filesToWatch.isEmpty()) {
                        addInfo("Empty watch file list. Disabling ");
                        return;
                    }
                    if (!configurationWatchList.changeDetected()) {
                        return;
                    }
                    loggerContext.reset();
                    configure(loggerContext);
                };

                loggerContext.fireConfigurationEvent(ConfigurationEvent.newConfigurationChangeDetectorRegisteredEvent(rocTask));
                loggerContext.addScheduledFuture(loggerContext
                        .getScheduledExecutorService()
                        .scheduleAtFixedRate(rocTask, delay, delay, TimeUnit.MILLISECONDS));
            }
        }
    }

}
