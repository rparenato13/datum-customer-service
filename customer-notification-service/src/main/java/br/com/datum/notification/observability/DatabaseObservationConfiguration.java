package br.com.datum.notification.observability;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@Profile("!smoke")
public class DatabaseObservationConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseObservationConfiguration.class);

    @Bean
    static BeanPostProcessor observedDataSource(ObjectProvider<Telemetry> telemetry) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source) || !"dataSource".equals(name)) return bean;
                return ProxyDataSourceBuilder.create(source).name("application")
                        .listener(new QueryExecutionListener() {
                            public void beforeQuery(ExecutionInfo info, List<QueryInfo> queries) { }
                            public void afterQuery(ExecutionInfo info, List<QueryInfo> queries) {
                                var observer = telemetry.getObject();
                                String operation = category(queries);
                                long elapsed = TimeUnit.MILLISECONDS.toNanos(info.getElapsedTime());
                                CorrelationContext.database(info.getElapsedTime());
                                observer.duration("datum.db.query.duration", elapsed, "statement", operation,
                                        "outcome", info.isSuccess() ? "success" : "error");
                                if (!info.isSuccess() || info.getElapsedTime() >= observer.slowQueryMs()) {
                                    observer.event(LOG, Level.WARN, "db.query", info.isSuccess() ? "slow" : "error", elapsed)
                                            .addKeyValue("statement", operation)
                                            .addKeyValue("exceptionClass", info.getThrowable() == null ? "none" : info.getThrowable().getClass().getSimpleName())
                                            .log("Acesso ao banco concluido sem registrar SQL ou parametros.");
                                }
                            }
                        }).build();
            }
        };
    }

    static String category(List<QueryInfo> queries) {
        if (queries.size() != 1 || queries.getFirst().getQuery() == null) return "other";
        String query = queries.getFirst().getQuery().stripLeading();
        int end = 0;
        while (end < query.length() && Character.isLetter(query.charAt(end))) end++;
        String keyword = query.substring(0, end).toLowerCase(Locale.ROOT);
        return Set.of("select", "insert", "update", "delete", "with").contains(keyword) ? keyword : "other";
    }
}
