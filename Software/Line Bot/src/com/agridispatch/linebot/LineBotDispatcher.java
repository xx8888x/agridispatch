package com.agridispatch.linebot;

import java.util.List;

/**
 * 智農派工 - LINE Bot 訊息分發入口
 * 依事件來自哪個官方帳號，交給農場主或工作者分派器（規格 07_系統分析及文件/05_LINE官方帳號拆分規格.md）。
 * 回傳要回覆的 LINE 訊息物件 JSON 清單（Reply API 一次最多 5 則）。
 */
public final class LineBotDispatcher {
    private LineBotDispatcher() {}

    public static final int MAX_REPLY_MESSAGES = 5;

    public static List<String> handle(BotEvent ev) {
        List<String> out = switch (ev.channel()) {
            case FARMER -> FarmerDispatcher.handle(ev);
            case WORKER -> WorkerDispatcher.handle(ev);
        };
        return out.size() > MAX_REPLY_MESSAGES ? out.subList(0, MAX_REPLY_MESSAGES) : out;
    }

    /** 文字訊息的便利寫法（TestSimulator、/mock/chat 用） */
    public static List<String> text(Channel channel, String userId, String text) {
        return handle(BotEvent.text(channel, userId, text));
    }
}
