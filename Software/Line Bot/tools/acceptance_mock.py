# -*- coding: utf-8 -*-
"""LINE 雙帳號：模擬環境驗收（規格 文件/07_系統分析及文件/05_LINE官方帳號拆分規格.md 第拾參章）

自動啟動一組隔離環境（暫存 H2 資料庫、後端 demo 模式、Line Bot 模擬模式，共用隨機內部金鑰），
逐條跑 15 條驗收情境，最後輸出 Markdown 結果表。不會動到開發用資料庫與 config.properties，結束時關閉所有程序。

用法（於 Software/Line Bot 目錄；需先能編譯後端，Maven 離線庫已備妥）：
    python tools/acceptance_mock.py [--report 輸出.md]

限制：Mock 模式不呼叫 LINE，「真的出現在手機上」「LIFF 取得真實 ID token」「圖文選單真的換掉」
要等帳號申請後依《LINE官方帳號上線與實機驗收手冊》在手機上確認。
"""
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

# Windows 主控台預設 cp950，印 ✅／❌ 會 UnicodeEncodeError：改成 UTF-8 輸出
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

LINEBOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOFTWARE = os.path.dirname(LINEBOT)
BACKEND = os.path.join(SOFTWARE, "backend")
MAVEN_HOME = os.path.join(BACKEND, "maven", "apache-maven-3.9.6")


def maven_cmd():
    """直接以 java 啟動 Maven（同 mvn 腳本內部做法）；mvn.cmd 遇到路徑含括號「(農事及其它)」會解析失敗"""
    import glob
    jar = glob.glob(os.path.join(MAVEN_HOME, "boot", "plexus-classworlds-*.jar"))[0]
    return ["java", "-classpath", jar,
            "-Dclassworlds.conf=" + os.path.join(MAVEN_HOME, "bin", "m2.conf"),
            "-Dmaven.home=" + MAVEN_HOME,
            "-Dmaven.multiModuleProjectDirectory=" + BACKEND,
            "org.codehaus.plexus.classworlds.launcher.Launcher"]

BE_PORT, LB_PORT, LEGACY_PORT, PROD_PORT = 3071, 5071, 5072, 5073
KEY = secrets.token_hex(16)
TMP = tempfile.mkdtemp(prefix="agri_line_acc_")
procs = []
results = []


# ----------------------------------------------------------------------------- 工具

def port_free(p):
    with socket.socket() as s:
        return s.connect_ex(("127.0.0.1", p)) != 0


def req(url, body=None, headers=None, method=None, raw=None):
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
    r = urllib.request.Request(url, data=data, method=method or ("POST" if data is not None else "GET"))
    r.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def jget(url, **kw):
    s, b = req(url, **kw)
    return s, (json.loads(b) if b.strip().startswith(("{", "[")) else b)


def wait_up(url, secs):
    end = time.time() + secs
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2)
            return True
        except urllib.error.HTTPError:
            return True
        except Exception:
            time.sleep(1)
    return False


def kill_port(port):
    """結束占用指定埠的程序（spring-boot:run 會另開 java 子程序）"""
    if os.name != "nt":
        return
    out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, errors="replace").stdout
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[1].endswith(":%d" % port) and parts[3] == "LISTENING":
            subprocess.run(["taskkill", "/PID", parts[4], "/T", "/F"], capture_output=True)


def kill(p, port=None):
    if p.poll() is None:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True)
        else:
            p.kill()
    try:
        p.wait(timeout=30)
    except Exception:
        pass
    if port:
        kill_port(port)
        for _ in range(30):
            if port_free(port):
                break
            time.sleep(0.5)


def start_backend(tag):
    env = dict(os.environ, SERVER_PORT=str(BE_PORT),
               SPRING_DATASOURCE_URL="jdbc:h2:file:" + os.path.join(TMP, "acc_db").replace("\\", "/") + ";DB_CLOSE_DELAY=-1",
               AGRI_LINEBOT_INTERNAL_KEY=KEY, AGRI_LINEBOT_BASE_URL="http://localhost:%d" % LB_PORT,
               AGRI_LINE_WORKER_HOURS="00:00-24:00")
    log = open(os.path.join(TMP, "backend_%s.log" % tag), "wb")
    p = subprocess.Popen(maven_cmd() + ["-o", "-q", "spring-boot:run", "-Dspring-boot.run.profiles=demo"],
                         cwd=BACKEND, env=env, stdout=log, stderr=subprocess.STDOUT)
    procs.append(p)
    if not wait_up("http://localhost:%d/api/public/config" % BE_PORT, 240):
        raise SystemExit("後端啟動逾時，請看 " + log.name)
    # HTTP 先開、種子資料（CommandLineRunner）稍後才建好：等到示範管理員能登入
    for _ in range(120):
        s, _ = req("http://localhost:%d/api/auth/login" % BE_PORT, body={"identifier": "admin_tech", "password": "admin888"})
        if s == 200:
            return p
        time.sleep(1)
    raise SystemExit("後端種子資料逾時，請看 " + log.name)


def linebot_dir(name):
    d = os.path.join(TMP, name)
    if not os.path.isdir(d):
        os.makedirs(d)
        for sub in ("flex_templates", "i18n", "rich_menu"):
            shutil.copytree(os.path.join(LINEBOT, sub), os.path.join(d, sub))
    return d


def start_linebot(tag, port, extra_env, cwd_name="lb_main"):
    env = {k: v for k, v in os.environ.items() if not k.startswith(("LINE_", "AGRI_LINEBOT", "LINEBOT_"))}
    env.update(PORT=str(port), BACKEND_API_BASE="http://localhost:%d/api" % BE_PORT)
    # 驗收情境含越南籍綁定、多語推播：外籍移工功能預設下架（feature.foreign-workers=false），
    # 這裡打開開關驗收恢復狀態；下架狀態由 TestSimulator --check 檢核
    env.update(FEATURE_FOREIGN_WORKERS="true")
    env.update(extra_env)
    log_path = os.path.join(TMP, "linebot_%s.log" % tag)
    log = open(log_path, "wb")
    p = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                          "-cp", os.path.join(TMP, "bin"), "com.agridispatch.linebot.LineBotServer"],
                         cwd=linebot_dir(cwd_name), env=env, stdout=log, stderr=subprocess.STDOUT)
    procs.append(p)
    if not wait_up("http://localhost:%d/health" % port, 30):
        raise SystemExit("Line Bot 啟動逾時，請看 " + log_path)
    return p, log_path


def compile_linebot():
    srcs = [os.path.join(LINEBOT, "src", "com", "agridispatch", "linebot", f)
            for f in os.listdir(os.path.join(LINEBOT, "src", "com", "agridispatch", "linebot")) if f.endswith(".java")]
    r = subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", os.path.join(TMP, "bin")] + srcs,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("Line Bot 編譯失敗：\n" + r.stderr[-2000:])


def record(no, title, ok, evidence, note=""):
    results.append((no, title, ok, evidence, note))
    print(("✅" if ok else "❌") + " #%s %s — %s" % (no, title, evidence))


# ----------------------------------------------------------------------------- 情境用的呼叫

LB = "http://localhost:%d" % LB_PORT
BE = "http://localhost:%d" % BE_PORT


def chat(channel, uid, **f):
    s, d = jget(LB + "/mock/chat", body=dict(f, channel=channel, userId=uid, multi=True))
    assert s == 200, (s, d)
    return d


def alt(msg):
    return msg.get("altText") or msg.get("text") or ""


def texts(d):
    return " | ".join(alt(m) for m in d.get("messages", []))


def login(identifier, password="123456"):
    s, d = jget(BE + "/api/auth/login", body={"identifier": identifier, "password": password})
    assert s == 200, (identifier, s, d)
    return {"Authorization": "Bearer " + d["token"]}


def bind(identifier, channel, uid):
    h = login(identifier)
    req(BE + "/api/line/bind/" + channel, headers=h, method="DELETE")
    return jget(BE + "/api/line/bind", body={"channel": channel, "idToken": "mock:" + uid}, headers=h)


def identity(channel, uid):
    return jget(BE + "/api/line/internal/identity", body={"channel": channel, "lineUserId": uid}, headers={"X-Internal-Key": KEY})[1]


def outbox_since(seq):
    return jget(LB + "/mock/outbox?since=%d" % seq)[1]


def uid(n):
    return "U%032x" % (0xacc000 + n)


# ----------------------------------------------------------------------------- 驗收

def run_main_checks():
    admin = login("admin_tech", "admin888")

    # 1 未綁定者加農場主帳號好友
    d = chat("farmer", uid(1), event="follow", **{"as": "unbound"})
    record(1, "未綁定者加農場主帳號好友", "農場主帳號綁定" in json.dumps(d, ensure_ascii=False) and d["menuKey"] == "farmer_guest",
           "歡迎＋綁定卡，選單 %s" % d["menuKey"], "Mock 模式的農場主未綁定時預設為示範農場主，故以 as=unbound 模擬")

    # 2 未綁定者加工作者帳號好友（越語介面）
    d = chat("worker", uid(2), event="follow", lineLanguage="vi")
    record(2, "未綁定者加工作者帳號好友（LINE 越語）", "Chào mừng" in texts(d) and "Liên kết" in texts(d) and d["menuKey"] == "worker_guest",
           "三語歡迎、越語綁定卡，選單 %s；後端語言 %s" % (d["menuKey"], identity("worker", uid(2)).get("lang")))

    # 3 越南籍工人完成綁定
    s, b = bind("W001", "worker", uid(3))
    d = chat("worker", uid(3), text="nhiệm vụ")
    record(3, "越南籍工人完成綁定", s == 200 and "Thẻ Nhiệm Vụ" in texts(d) and d["menuKey"] == "worker_main_vi",
           "綁定 %s；回「%s」，選單 %s" % (s, alt(d["messages"][0])[:24], d["menuKey"]))

    # 4（第一段）越南籍工人點「中文」
    d = chat("worker", uid(3), postback="a=lang&v=zh")
    idt = identity("worker", uid(3))
    four_a = d["menuKey"] == "worker_main_zh" and idt.get("langLocked") is True and idt.get("lang") == "zh"

    # 5 本勞（中文）點今日任務
    bind("W030", "worker", uid(5))
    d = chat("worker", uid(5), postback="a=task")
    record(5, "本勞（中文）點今日任務", "今日派工任務卡" in texts(d) and "派工確認單" not in texts(d), "回「%s」" % alt(d["messages"][0])[:24])

    # 6 機耕師點今日任務
    bind("W012", "worker", uid(6))
    d = chat("worker", uid(6), postback="a=task")
    record(6, "機耕師點今日任務", "代噴任務卡" in texts(d), "回「%s」" % alt(d["messages"][0])[:26])

    # 7 工作者帳號問農場主功能
    outs = [texts(chat("worker", uid(5), text=t)) for t in ("黑轉白", "早鳥", "費率")]
    record(7, "工作者帳號輸入「黑轉白」「早鳥」「費率」", all("此帳號提供工作人員使用" in o and "黑轉白：" not in o for o in outs),
           "三則都回「此帳號提供工作人員使用」引導")

    # 8 農場主帳號問工作者功能
    bind("farmer", "farmer", uid(8))
    o = texts(chat("farmer", uid(8), text="薪資"))
    record(8, "農場主帳號輸入「薪資」", "此帳號提供農場主使用" in o, "回「%s」" % o[:24])

    # 9 農場主身分綁工作者帳號
    s, b = jget(BE + "/api/line/bind", body={"channel": "worker", "idToken": "mock:" + uid(9)}, headers=login("farmer"))
    record(9, "農場主身分在工作者帳號綁定", s == 409 and "智農派工（農場主）" in json.dumps(b, ensure_ascii=False), "HTTP %s，引導至農場主帳號" % s)

    # 10 竄改 userId：後端只採信驗證過的 ID token
    victim = uid(10)
    s, b = jget(BE + "/api/line/bind", body={"channel": "worker", "idToken": "forged.header.token", "lineUserId": victim},
                headers=login("W004"))
    record(10, "前端竄改送出他人 userId 綁定", s in (401, 503) and identity("worker", victim).get("bound") is False,
           "HTTP %s，該 userId 仍未綁定" % s, "demo 未設 LINE Login 時以 mock: 假 token 模擬；正式模式拒絕假 token（後端 LineBindingProductionTest），真實 ID token 待實機")

    # 12 錯誤內部金鑰
    s1, _ = req(BE + "/api/line/internal/identity", body={"channel": "worker", "lineUserId": uid(3)}, headers={"X-Internal-Key": "wrong"})
    s2, _ = req(LB + "/internal/notify", body={"channel": "worker", "type": "tomorrow_task", "params": {}, "recipients": []}, headers={"X-Internal-Key": "wrong"})
    s3, _ = req(LB + "/internal/richmenu/link", body={"channel": "worker", "lineUserId": uid(3), "menuKey": "worker_main_zh"})
    record(12, "未帶或錯誤 X-Internal-Key 呼叫內部端點", s1 == 403 and s2 == 403 and s3 == 403, "後端 %s、Line Bot 推播 %s、選單 %s" % (s1, s2, s3))

    # 13／14 天候集合變更：ORD-2026-003（team_c：W012 機耕、W013 本國、W014 越南、W015 印尼）
    today, tomorrow = date.today(), date.today() + timedelta(days=1)
    s, b = jget(BE + "/api/orders/ORD-2026-003", body={"startDate": str(today), "endDate": str(tomorrow)}, headers=admin, method="PUT")
    assert s == 200, (s, b)
    bind("W013", "worker", uid(13))
    bind("W014", "worker", uid(14))
    bind("W015", "worker", uid(15))
    chat("worker", uid(13), postback="a=help")  # 觸發一次互動
    expected_crew = expected_active_crew(admin, today)
    bound = {"W001": uid(3), "W030": uid(5), "W012": uid(6), "W013": uid(13), "W014": uid(14), "W015": uid(15)}
    # 另綁一位「今天不在任何出工名單」的工人，確認名單外的人收不到
    _, ws = jget(BE + "/api/workers", headers=admin)
    ws = ws.get("data", ws) if isinstance(ws, dict) else ws
    outsider = next((w["id"] for w in ws if w["id"].upper() not in expected_crew and w["id"] not in bound), None)
    if not outsider:
        # 種子資料每個工班今天都有施工中訂單：在暫存資料庫把一位不在任何出工名單上的工人移到備用工班
        _, os_ = jget(BE + "/api/orders", headers=admin)
        os_ = os_.get("data", os_) if isinstance(os_, dict) else os_
        listed = {x.strip().upper() for o in os_ for x in (o.get("crewWorkerIds") or "").split(",") if x.strip()}
        cand = next((w["id"] for w in ws if w["id"].upper() not in listed and w["id"] not in bound), None)
        if cand:
            s_, _ = jget(BE + "/api/workers/" + cand, body={"teamId": "team_reserve"}, headers=admin, method="PUT")
            if s_ == 200:
                outsider = cand
                expected_crew = expected_active_crew(admin, today)
    if outsider:
        bind(outsider, "worker", uid(16))
        bound[outsider] = uid(16)
    want = {bound[w] for w in bound if w in expected_crew}

    def weather_round():
        jget(BE + "/api/dispatch/weather-scenario", body={"scenarioId": "sunny"}, headers=admin)
        last = outbox_since(0)["last"]
        jget(BE + "/api/dispatch/weather-scenario", body={"scenarioId": "typhoon"}, headers=admin)
        for _ in range(20):
            got = [e for e in outbox_since(last)["entries"] if e["type"] == "weather_worker"]
            if got:
                time.sleep(1)
                return [e for e in outbox_since(last)["entries"] if e["type"] == "weather_worker"]
            time.sleep(0.5)
        return []

    entries = weather_round()
    got = {u for e in entries for u in e["to"]}
    by_lang = {}
    for e in entries:
        by_lang[e["lang"]] = by_lang.get(e["lang"], 0) + len(e["to"])
    outsiders = [bound[w] for w in bound if w not in expected_crew]
    record(14, "天候集合變更，名單含越、印、中三語工人",
           got == want and {"vi", "id", "zh"} <= set(by_lang) and len(outsiders) > 0 and not (set(outsiders) & got),
           "收件 %d 人，依語言 %s；名單外已綁定 %d 人（%s）未收到" % (len(got), by_lang, len(outsiders),
                                                         "、".join(w for w in bound if w not in expected_crew) or "無"))

    # 13 封鎖後不推
    chat("worker", uid(15), event="unfollow")
    entries = weather_round()
    got2 = {u for e in entries for u in e["to"]}
    record(13, "使用者封鎖工作者帳號後發布天候通知", uid(15) not in got2 and (want - {uid(15)}) <= got2,
           "W015 封鎖後不在名單（收件 %d 人）" % len(got2))
    return four_a


def expected_active_crew(admin, day):
    """依後端資料算出「今天在工期內或沒填工期的施工中訂單」的出工名單（與 LineNotifyService 規則相同）"""
    _, orders = jget(BE + "/api/orders", headers=admin)
    _, workers = jget(BE + "/api/workers", headers=admin)
    orders = orders.get("data", orders) if isinstance(orders, dict) else orders
    workers = workers.get("data", workers) if isinstance(workers, dict) else workers
    crew = set()
    for o in orders:
        st = o.get("status")
        if st not in ("confirmed", "in_progress"):
            continue
        sd, ed = o.get("startDate"), o.get("endDate")
        active = (sd and ed and sd <= str(day) <= ed) or (not (sd and ed) and st == "in_progress")
        if not active:
            continue
        ids = [x for x in (o.get("crewWorkerIds") or "").split(",") if x.strip()]
        if not ids:
            ids = [w["id"] for w in workers if w.get("teamId") == o.get("assignedTeamId")]
        crew.update(i.strip().upper() for i in ids)
    return crew


def check_after_restart(four_a):
    d = chat("worker", uid(3), text="任務")
    idt = identity("worker", uid(3))
    record(4, "越南籍工人點「中文」，重啟後仍為中文",
           four_a and "今日派工任務卡" in texts(d) and d["menuKey"] == "worker_main_zh" and idt.get("langLocked") is True,
           "選單 worker_main_zh、lang_locked=Y；後端與 Line Bot 都重啟後回「%s」" % alt(d["messages"][0])[:20])


def check_signature_and_legacy():
    # 11 兩帳號正式模式（假金鑰），只送不需回覆的 unfollow，避免呼叫 LINE
    import base64, hashlib, hmac
    p, _ = start_linebot("prod", PROD_PORT, {"LINE_FARMER_CHANNEL_SECRET": "acc_farmer_secret", "LINE_FARMER_CHANNEL_ACCESS_TOKEN": "acc_farmer_token",
                                             "LINE_WORKER_CHANNEL_SECRET": "acc_worker_secret", "LINE_WORKER_CHANNEL_ACCESS_TOKEN": "acc_worker_token"},
                         cwd_name="lb_prod")
    raw = json.dumps({"events": [{"type": "unfollow", "source": {"userId": uid(11)}}]}).encode()
    sig = lambda k: base64.b64encode(hmac.new(k.encode(), raw, hashlib.sha256).digest()).decode()
    url = "http://localhost:%d" % PROD_PORT
    s_bad, _ = req(url + "/callback/worker", raw=raw, headers={"X-Line-Signature": sig("acc_farmer_secret")})
    s_ok, _ = req(url + "/callback/worker", raw=raw, headers={"X-Line-Signature": sig("acc_worker_secret")})
    record(11, "以農場主帳號 secret 簽章送到 /callback/worker", s_bad == 403 and s_ok == 200, "農場主簽章 %s、工作者簽章 %s" % (s_bad, s_ok))
    kill(p, PROD_PORT)

    # 15 只設舊的 LINE_CHANNEL_*
    p, log_path = start_linebot("legacy", LEGACY_PORT, {"LINE_CHANNEL_SECRET": "acc_legacy_secret", "LINE_CHANNEL_ACCESS_TOKEN": "acc_legacy_token"},
                                cwd_name="lb_legacy")
    _, h = jget("http://localhost:%d/health" % LEGACY_PORT)
    warned = "偵測到舊的單一帳號設定" in open(log_path, encoding="utf-8", errors="replace").read()
    record(15, "只設定舊 LINE_CHANNEL_SECRET 啟動", h["channels"] == {"farmer": "PRODUCTION", "worker": "MOCK"} and warned,
           "農場主 %s、工作者 %s，log 有警告" % (h["channels"]["farmer"], h["channels"]["worker"]))
    kill(p, LEGACY_PORT)


def main():
    report = None
    if "--report" in sys.argv:
        report = sys.argv[sys.argv.index("--report") + 1]
    for p in (BE_PORT, LB_PORT, LEGACY_PORT, PROD_PORT):
        if not port_free(p):
            raise SystemExit("埠 %d 已被使用，請先關閉" % p)
    print("暫存目錄：", TMP)
    compile_linebot()
    try:
        be = start_backend("1")
        lb, _ = start_linebot("1", LB_PORT, {"AGRI_LINEBOT_INTERNAL_KEY": KEY})
        four_a = run_main_checks()
        # 重啟後端與 Line Bot（同一個資料庫）
        kill(lb, LB_PORT)
        kill(be, BE_PORT)
        procs.clear()
        be = start_backend("2")
        lb, _ = start_linebot("2", LB_PORT, {"AGRI_LINEBOT_INTERNAL_KEY": KEY})
        check_after_restart(four_a)
        check_signature_and_legacy()
    finally:
        for p in procs:
            try:
                kill(p)
            except Exception:
                pass
        for port in (BE_PORT, LB_PORT, LEGACY_PORT, PROD_PORT):
            kill_port(port)

    results.sort(key=lambda r: r[0])
    lines = ["| # | 情境 | 結果 | 證據 | 備註 |", "|---|---|---|---|---|"]
    for no, title, ok, ev, note in results:
        lines.append("| %d | %s | %s | %s | %s |" % (no, title, "✅" if ok else "❌", ev.replace("|", "／"), note))
    table = "\n".join(lines)
    print("\n" + table)
    failed = sum(1 for r in results if not r[2])
    print("\n%d / %d 通過（%s）" % (len(results) - failed, len(results), date.today()))
    if report:
        with open(report, "w", encoding="utf-8", newline="\n") as f:
            f.write(table + "\n")
    shutil.rmtree(TMP, ignore_errors=True)
    sys.exit(1 if failed or len(results) != 15 else 0)


if __name__ == "__main__":
    main()
