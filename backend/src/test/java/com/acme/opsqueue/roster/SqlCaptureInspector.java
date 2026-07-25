package com.acme.opsqueue.roster;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public final class SqlCaptureInspector implements StatementInspector {
    private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

    public static void reset() { STATEMENTS.get().clear(); }
    public static List<String> statements() { return List.copyOf(STATEMENTS.get()); }

    @Override
    public String inspect(String sql) {
        STATEMENTS.get().add(sql);
        return sql;
    }
}
