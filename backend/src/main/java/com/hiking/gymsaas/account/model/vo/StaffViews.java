package com.hiking.gymsaas.account.model.vo;

import java.time.OffsetDateTime;
import java.util.List;

/** 员工视图；不返回密码哈希，ID 一律字符串。 */
public final class StaffViews {
    private StaffViews() { }

    public record Staff(String id, String username, String role, List<String> permissions,
                        String status, OffsetDateTime createdAt) {
        public Staff {
            permissions = List.copyOf(permissions);
        }
    }
}
