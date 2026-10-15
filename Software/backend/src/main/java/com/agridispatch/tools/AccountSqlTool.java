package com.agridispatch.tools;

import com.agridispatch.security.PasswordHasher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 正式上線開帳號：讀帳號清單 CSV，產生寫入 users 表的 SQL（Oracle／H2 皆可執行的 MERGE）與發給使用者的帳密清單。
 * 密碼以後端同一套 PasswordHasher（PBKDF2）雜湊，SQL 內只有雜湊、沒有明碼；明碼只出現在帳密清單檔。
 *
 * <pre>
 * 用法（於 Software/backend，先 mvn -o compile）：
 *   java -cp target/classes com.agridispatch.tools.AccountSqlTool &lt;帳號清單.csv&gt; &lt;輸出.sql&gt; &lt;帳密清單.csv&gt; [--reset]
 * 帳號清單格式（與路演帳號 roadshow-accounts.csv 相同，# 開頭為註解）：
 *   username,password,name,role,refId,roleDesc
 *   password 留空 → 自動產生 12 碼隨機密碼；role 為 admin_tech／farmer／worker_foreign／worker_local
 * 預設只新增不存在的帳號（既有帳號與其密碼不動）；加 --reset 則既有帳號也改成新密碼與資料。
 * </pre>
 * 輸出檔含密碼雜湊與明碼，請放在 gitignore 的位置（建議 Software/database/local/），發送後刪除帳密清單。
 */
public final class AccountSqlTool {

    private static final Set<String> ROLES = Set.of("admin_tech", "coop_admin", "farmer", "worker_foreign", "worker_local");
    /** 去掉易混淆字元（0/O、1/l/I） */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccountSqlTool() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法：AccountSqlTool <帳號清單.csv> <輸出.sql> <帳密清單.csv> [--reset]");
            System.exit(2);
        }
        boolean reset = args.length > 3 && "--reset".equals(args[3]);
        List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        PasswordHasher hasher = new PasswordHasher();

        StringBuilder sql = new StringBuilder();
        sql.append("-- 智農派工 正式帳號（由 AccountSqlTool 於 ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" 產生）\n-- 含密碼雜湊，勿提交版本庫。")
                .append(reset ? "既有帳號會被改成新密碼。" : "只新增不存在的帳號，既有帳號不動。").append("\n\n");
        StringBuilder creds = new StringBuilder("﻿帳號,密碼,姓名,角色,說明\n");
        int count = 0;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("﻿")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",", 6);
            if (f.length < 6 || f[0].isBlank() || !ROLES.contains(f[3].strip())) {
                throw new IllegalArgumentException("格式錯誤的列（需 username,password,name,role,refId,roleDesc）：" + f[0]);
            }
            String username = f[0].strip();
            String password = f[1].strip().isEmpty() ? randomPassword() : f[1].strip();
            if (password.length() < 8) throw new IllegalArgumentException(username + " 的密碼少於 8 字元");
            String name = f[2].strip(), role = f[3].strip(), refId = f[4].strip(), roleDesc = f[5].strip();

            sql.append("MERGE INTO users u USING (SELECT ").append(q(username)).append(" AS username FROM dual) s\n")
               .append("  ON (u.username = s.username)\n");
            if (reset) {
                sql.append("  WHEN MATCHED THEN UPDATE SET u.password = ").append(q(hasher.hash(password)))
                   .append(", u.name = ").append(q(name)).append(", u.role = ").append(q(role))
                   .append(", u.ref_id = ").append(q(refId)).append(", u.role_desc = ").append(q(roleDesc)).append("\n");
            }
            sql.append("  WHEN NOT MATCHED THEN INSERT (username, password, name, role, ref_id, role_desc)\n")
               .append("  VALUES (").append(q(username)).append(", ").append(q(hasher.hash(password))).append(", ")
               .append(q(name)).append(", ").append(q(role)).append(", ").append(q(refId)).append(", ")
               .append(q(roleDesc)).append(");\n\n");
            creds.append(csv(username)).append(',').append(csv(password)).append(',').append(csv(name)).append(',')
                 .append(csv(role)).append(',').append(csv(roleDesc)).append('\n');
            count++;
        }
        sql.append("COMMIT;\n");
        Files.writeString(Path.of(args[1]), sql.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of(args[2]), creds.toString(), StandardCharsets.UTF_8);
        System.out.println("[AccountSqlTool] " + count + " 個帳號 → " + args[1] + "；帳密清單 → " + args[2]);
    }

    static String randomPassword() {
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String p = sb.toString();
            // 大寫、小寫、數字各至少一個
            if (p.chars().anyMatch(Character::isUpperCase) && p.chars().anyMatch(Character::isLowerCase)
                    && p.chars().anyMatch(Character::isDigit)) return p;
        }
    }

    /** SQL 字串常值（單引號跳脫） */
    private static String q(String v) {
        return v == null || v.isEmpty() ? "NULL" : "'" + v.replace("'", "''") + "'";
    }

    private static String csv(String v) {
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
