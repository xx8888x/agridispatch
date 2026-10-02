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
        // 追蹤狀態先記錄到後端（規格 5.1）；LINE 介面語言格式如 zh-TW、vi、id，只取主語言碼
        if (ev.type() == BotEvent.Type.FOLLOW) {
            String pl = ev.profileLang() == null ? null : ev.profileLang().split("-")[0].toLowerCase();
            IdentityStore.recordFollow(ev.channel(), ev.userId(), pl);
        } else if (ev.type() == BotEvent.Type.UNFOLLOW) {
            IdentityStore.recordUnfollow(ev.channel(), ev.userId());
        }
        List<String> out = switch (ev.channel()) {
            case FARMER -> FarmerDispatcher.handle(ev);
            case WORKER -> WorkerDispatcher.handle(ev);
        };
        // 圖文選單跟著身分與語言走（規格第捌章）：綁定、解除、改語言後下一次互動即切換
        if (ev.type() == BotEvent.Type.UNFOLLOW) {
            RichMenus.forget(ev.channel(), ev.userId());
        } else {
            RichMenus.ensure(ev.channel(), ev.userId(), IdentityStore.get(ev.channel(), ev.userId()));
        }
        return out.size() > MAX_REPLY_MESSAGES ? out.subList(0, MAX_REPLY_MESSAGES) : out;
    }

    /** 文字訊息的便利寫法（TestSimulator、/mock/chat 用） */
    public static List<String> text(Channel channel, String userId, String text) {
        return handle(BotEvent.text(channel, userId, text));
    }
}
