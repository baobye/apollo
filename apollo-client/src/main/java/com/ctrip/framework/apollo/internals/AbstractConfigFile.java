package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.ConfigFile;
import com.ctrip.framework.apollo.ConfigFileChangeListener;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.enums.PropertyChangeType;
import com.ctrip.framework.apollo.model.ConfigFileChangeEvent;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.apollo.util.ExceptionUtil;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ConfigFile 抽象类
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public abstract class AbstractConfigFile implements ConfigFile, RepositoryChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(AbstractConfigFile.class);

    /**
     * ExecutorService 对象，用于配置变化时，异步通知 ConfigChangeListener 监听器们
     *
     * 静态属性，所有 Config 共享该线程池。
     */
    private static ExecutorService m_executorService;

    /**
     * Namespace 的名字
     */
    protected String m_namespace;
    /**
     * ConfigChangeListener 集合
     */
    private List<ConfigFileChangeListener> m_listeners = Lists.newCopyOnWriteArrayList();

    protected ConfigRepository m_configRepository;
    /**
     * 配置 Properties 的缓存引用
     */
    protected AtomicReference<Properties> m_configProperties;

    static {
        m_executorService = Executors.newCachedThreadPool(ApolloThreadFactory.create("ConfigFile", true));
    }

    public AbstractConfigFile(String namespace, ConfigRepository configRepository) {
        m_configRepository = configRepository;
        m_namespace = namespace;
        m_configProperties = new AtomicReference<>();
        // 初始化
        initialize();
    }

    private void initialize() {
        try {
            // 初始化 m_configProperties
            m_configProperties.set(m_configRepository.getConfig());
        } catch (Throwable ex) {
            Tracer.logError(ex);
            logger.warn("Init Apollo Config File failed - namespace: {}, reason: {}.", m_namespace, ExceptionUtil.getDetailMessage(ex));
        } finally {
            //register the change listener no matter config repository is working or not
            //so that whenever config repository is recovered, config could get changed
            // 注册到 ConfigRepository 中，从而实现每次配置发生变更时，更新配置缓存 `m_configProperties` 。
            m_configRepository.addChangeListener(this);
        }
    }

    @Override
    public String getNamespace() {
        return m_namespace;
    }

    protected abstract void update(Properties newProperties);

    @Override
    public synchronized void onRepositoryChange(String namespace, Properties newProperties) {
        // 忽略，若未变更
        if (newProperties.equals(m_configProperties.get())) {
            return;
        }
        // 读取新的 Properties 对象
        Properties newConfigProperties = new Properties();
        newConfigProperties.putAll(newProperties);

        // 获得【旧】值
        String oldValue = getContent();
        // 更新为【新】值
        update(newProperties);
        // 获得新值
        String newValue = getContent();

        // 计算变化类型
        PropertyChangeType changeType = PropertyChangeType.MODIFIED;
        if (oldValue == null) {
            changeType = PropertyChangeType.ADDED;
        } else if (newValue == null) {
            changeType = PropertyChangeType.DELETED;
        }

        // 通知监听器们
        this.fireConfigChange(new ConfigFileChangeEvent(m_namespace, oldValue, newValue, changeType));

        Tracer.logEvent("Apollo.Client.ConfigChanges", m_namespace);
    }

    @Override
    public void addChangeListener(ConfigFileChangeListener listener) {
        if (!m_listeners.contains(listener)) {
            m_listeners.add(listener);
        }
    }

    private void fireConfigChange(final ConfigFileChangeEvent changeEvent) {
        // 缓存 ConfigChangeListener 数组
        for (final ConfigFileChangeListener listener : m_listeners) {
            m_executorService.submit(new Runnable() {
                @Override
                public void run() {
                    String listenerName = listener.getClass().getName();
                    Transaction transaction = Tracer.newTransaction("Apollo.ConfigFileChangeListener", listenerName);
                    try {
                        // 通知监听器
                        listener.onChange(changeEvent);
                        transaction.setStatus(Transaction.SUCCESS);
                    } catch (Throwable ex) {
                        transaction.setStatus(ex);
                        Tracer.logError(ex);
                        logger.error("Failed to invoke config file change listener {}", listenerName, ex);
                    } finally {
                        transaction.complete();
                    }
                }
            });
        }
    }

}