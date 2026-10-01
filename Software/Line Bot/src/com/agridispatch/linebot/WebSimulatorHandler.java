package com.agridispatch.linebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 智農派工 - LINE Bot 網頁模擬器處理器
 * 當使用者於瀏覽器開啟 http://localhost:5000/ 時，提供擬真的手機 LINE 聊天室與六宮格選單，支援即時點擊與對話測試！
 */
public class WebSimulatorHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path) && !"/simulator".equals(path)) {
            byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound);
            }
            return;
        }

        String html = getSimulatorHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String getSimulatorHtml() {
        return """
        <!DOCTYPE html>
        <html lang="zh-TW">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>智農派工 (AgriDispatch) - LINE Bot 實機體驗與測試台</title>
          <script src="https://cdn.tailwindcss.com"></script>
          <style>
            .line-bg { background-color: #8CABD9; background-image: radial-gradient(#9bb8e3 1px, transparent 1px); background-size: 16px 16px; }
            .user-bubble { background-color: #85E249; border-radius: 18px 18px 4px 18px; }
            .bot-bubble { background-color: #FFFFFF; border-radius: 18px 18px 18px 4px; }
            .custom-scrollbar::-webkit-scrollbar { width: 6px; }
            .custom-scrollbar::-webkit-scrollbar-track { background: transparent; }
            .custom-scrollbar::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.15); border-radius: 3px; }
          </style>
        </head>
        <body class="bg-slate-900 text-slate-100 min-h-screen flex flex-col antialiased">
          
          <!-- Top Header -->
          <header class="bg-slate-950 border-b border-slate-800 px-6 py-4 flex items-center justify-between shadow-md">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-600 to-green-400 flex items-center justify-center shadow-lg shadow-emerald-500/20 text-xl font-bold">
                🌱
              </div>
              <div>
                <h1 class="text-base font-black text-white flex items-center gap-2">
                  智農派工 AgriDispatch
                  <span class="text-[11px] px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 font-semibold">
                    Java LINE Bot 測試台
                  </span>
                </h1>
                <p class="text-xs text-slate-400">零相依純 Java 伺服器 ╳ 六宮格圖文選單 ╳ Flex Message 即時渲染</p>
              </div>
            </div>
            
            <div class="flex items-center gap-3 text-xs">
              <span class="px-2.5 py-1 rounded-md bg-emerald-950 text-emerald-300 border border-emerald-800 flex items-center gap-1.5 font-mono">
                <span class="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span> 服務運行中 :5000
              </span>
              <a href="#guide" class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-bold transition">
                📱 手機真實 LINE 串接步驟
              </a>
            </div>
          </header>

          <!-- Main Layout -->
          <main class="flex-1 max-w-7xl w-full mx-auto px-4 py-6 grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            
            <!-- Left: Smartphone LINE Simulator (7 cols) -->
            <section class="lg:col-span-7 flex flex-col items-center">
              
              <!-- Smartphone Mockup Frame -->
              <div class="w-full max-w-[420px] bg-slate-950 rounded-[44px] p-3.5 shadow-2xl border-4 border-slate-800 flex flex-col h-[780px] relative">
                
                <!-- Notch / Island -->
                <div class="w-28 h-4 bg-slate-900 rounded-full mx-auto mb-2 flex items-center justify-center">
                  <div class="w-2.5 h-2.5 rounded-full bg-slate-950 ml-6"></div>
                </div>

                <!-- LINE Chat Header -->
                <div class="bg-[#243342] text-white px-4 py-2.5 rounded-t-[28px] flex items-center justify-between shadow-sm">
                  <div class="flex items-center gap-2.5">
                    <div class="w-9 h-9 rounded-full bg-white text-emerald-700 flex items-center justify-center font-bold text-lg shadow-sm">
                      🌱
                    </div>
                    <div>
                      <div class="text-sm font-bold flex items-center gap-1.5">
                        智農派工官方助理
                        <span class="text-[10px] px-1.5 py-0.2 rounded bg-emerald-600 text-white font-medium">認證帳號</span>
                      </div>
                      <div class="text-[10px] text-slate-300">通常於幾秒內回覆</div>
                    </div>
                  </div>
                  <div class="text-slate-400 text-lg cursor-pointer">⋮</div>
                </div>

                <!-- LINE Chat Messages Area -->
                <div id="chat-box" class="flex-1 line-bg p-3.5 overflow-y-auto space-y-3 custom-scrollbar">
                  
                  <!-- Welcome Message -->
                  <div class="flex items-start gap-2">
                    <div class="w-7 h-7 rounded-full bg-white text-emerald-700 flex items-center justify-center text-xs shadow flex-shrink-0">
                      🌱
                    </div>
                    <div class="max-w-[85%]">
                      <div class="bot-bubble p-3 text-slate-800 text-xs shadow leading-relaxed">
                        👋 歡迎使用<b>智農派工</b> LINE 官方助理！<br/>
                        您可以點擊下方<b>六宮格圖文選單</b>，或直接輸入指令（例如「派工」、「避險」、「早鳥」、「機耕」、「薪資」）。
                      </div>
                      <span class="text-[9px] text-slate-700 ml-1 mt-0.5 inline-block">剛剛</span>
                    </div>
                  </div>

                  <div id="chat-anchor"></div>
                </div>

                <!-- Rich Menu (六宮格圖文選單) -->
                <div class="bg-slate-900 border-t border-slate-800 p-1.5">
                  <div class="text-[10px] text-slate-400 text-center mb-1 font-semibold">▼ 六宮格圖文選單 (點擊即時觸發) ▼</div>
                  <div class="grid grid-cols-3 gap-1">
                    <button onclick="sendKeyword('今日派工')" class="p-2.5 rounded-lg bg-emerald-800 hover:bg-emerald-700 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">📋</span>
                      <span class="text-[11px] font-bold">今日派工</span>
                    </button>
                    <button onclick="sendKeyword('天候避險')" class="p-2.5 rounded-lg bg-blue-900 hover:bg-blue-800 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">🌧️</span>
                      <span class="text-[11px] font-bold">暴雨避險</span>
                    </button>
                    <button onclick="sendKeyword('早鳥工時包')" class="p-2.5 rounded-lg bg-amber-800 hover:bg-amber-700 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">⚡</span>
                      <span class="text-[11px] font-bold">早鳥工時包</span>
                    </button>
                    <button onclick="sendKeyword('切換語言')" class="p-2.5 rounded-lg bg-indigo-900 hover:bg-indigo-800 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">🌐</span>
                      <span class="text-[11px] font-bold">切換語言</span>
                    </button>
                    <button onclick="sendKeyword('機耕代噴')" class="p-2.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">🚜</span>
                      <span class="text-[11px] font-bold">機耕代噴</span>
                    </button>
                    <button onclick="sendKeyword('帳號綁定')" class="p-2.5 rounded-lg bg-teal-800 hover:bg-teal-700 text-white flex flex-col items-center transition active:scale-95 shadow">
                      <span class="text-base mb-0.5">👤</span>
                      <span class="text-[11px] font-bold">帳號綁定</span>
                    </button>
                  </div>
                </div>

                <!-- Input Footer -->
                <div class="bg-slate-950 p-2.5 rounded-b-[36px] flex items-center gap-2 border-t border-slate-900">
                  <input id="user-input" type="text" placeholder="輸入訊息... (如: 派工, 避險, 薪資)" 
                         class="flex-1 bg-slate-900 text-white text-xs px-3.5 py-2.5 rounded-full outline-none border border-slate-800 focus:border-emerald-500 transition"
                         onkeydown="if(event.key==='Enter') sendMessage()" />
                  <button onclick="sendMessage()" class="w-9 h-9 rounded-full bg-emerald-600 hover:bg-emerald-500 text-white flex items-center justify-center font-bold text-xs shadow transition active:scale-95">
                    ➤
                  </button>
                </div>

              </div>
            </section>

            <!-- Right: Quick Test Buttons & Mobile Setup Guide (5 cols) -->
            <section class="lg:col-span-5 space-y-6">
              
              <!-- Quick Test Panel -->
              <div class="bg-slate-950 rounded-2xl border border-slate-800 p-5 shadow-lg">
                <h2 class="text-sm font-bold text-emerald-400 mb-3 flex items-center gap-2">
                  ⚡ 快速指令測試 (點擊立即於模擬器送出)
                </h2>
                <div class="grid grid-cols-2 gap-2 text-xs">
                  <button onclick="sendKeyword('派工')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    📋 派工單 (農場主視角)
                  </button>
                  <button onclick="sendKeyword('避險')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    🌧️ 暴雨避險通報
                  </button>
                  <button onclick="sendKeyword('早鳥')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    ⚡ 早鳥工時包・鎖定名額
                  </button>
                  <button onclick="sendKeyword('機耕')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    🚜 大疆 T50 機耕工單
                  </button>
                  <button onclick="sendKeyword('薪資')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    💰 農業部 $8,800 獎勵
                  </button>
                  <button onclick="sendKeyword('越南語')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    🇻🇳 切換越南語
                  </button>
                  <button onclick="sendKeyword('nhiệm vụ')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    🇻🇳 越語出工任務卡
                  </button>
                  <button onclick="sendKeyword('tugas')" class="p-2 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-left font-medium transition">
                    🇮🇩 印尼語任務卡
                  </button>
                </div>
              </div>

              <!-- Real Mobile LINE Setup Guide -->
              <div id="guide" class="bg-slate-950 rounded-2xl border border-slate-800 p-5 shadow-lg space-y-4">
                <div class="flex items-center justify-between">
                  <h2 class="text-sm font-bold text-white flex items-center gap-2">
                    📱 如何在真實手機 LINE 上測試？
                  </h2>
                  <span class="text-[10px] px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800 font-bold">
                    4 步驟即時串接
                  </span>
                </div>

                <ol class="text-xs space-y-3 text-slate-300 list-decimal list-inside leading-relaxed">
                  <li class="pl-1">
                    <b class="text-white">啟動外網穿透工具 (ngrok)</b><br/>
                    <span class="text-slate-400">開啟終端機執行：</span>
                    <code class="block mt-1 p-2 rounded bg-slate-900 border border-slate-800 font-mono text-emerald-400">ngrok http 5000</code>
                    <span class="text-[11px] text-slate-400">複製產生的 HTTPS 網址 (例如 <code>https://xxxx.ngrok-free.app</code>)。</span>
                  </li>
                  
                  <li class="pl-1">
                    <b class="text-white">至 LINE Developers 後台設定 Webhook</b><br/>
                    <span class="text-slate-400">登入 <a href="https://developers.line.biz/" target="_blank" class="text-emerald-400 underline">LINE Developers Console</a> 進入您的 Messaging API Channel：</span>
                    <ul class="list-disc list-inside ml-2 mt-1 text-slate-400">
                      <li><b>Webhook URL</b> 填入：<code>https://xxxx.ngrok-free.app/callback</code></li>
                      <li>開啟 <b>Use webhook</b> 開關</li>
                      <li>點擊 <b>Verify</b> 驗證成功 (Success)</li>
                    </ul>
                  </li>

                  <li class="pl-1">
                    <b class="text-white">填入金鑰至 config.properties</b><br/>
                    <span class="text-slate-400">在 <code>Software/Line Bot/config.properties</code> 填入您的 Channel Secret 與 Channel Access Token。</span>
                  </li>

                  <li class="pl-1">
                    <b class="text-white">用手機 LINE 掃描 QR Code 加好友</b><br/>
                    <span class="text-slate-400">在 LINE Developers Channel 的 <b>Messaging API</b> 頁籤中，用手機掃描 QR Code 加為好友，即可直接在手機上輸入「派工」、「避險」等即時互動！</span>
                  </li>
                </ol>
              </div>

            </section>

          </main>

          <script>
            async function sendKeyword(text) {
              document.getElementById('user-input').value = text;
              await sendMessage();
            }

            async function sendMessage() {
              const input = document.getElementById('user-input');
              const text = input.value.trim();
              if (!text) return;
              input.value = '';

              // Append user message
              appendUserMessage(text);

              // Send to backend /mock/chat
              try {
                const res = await fetch('/mock/chat', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ text: text })
                });
                const data = await res.json();
                appendBotReply(data);
              } catch (e) {
                appendBotText("❌ 伺服器通訊異常，請確認 Java 後台仍在運行中。");
              }
            }

            function appendUserMessage(text) {
              const anchor = document.getElementById('chat-anchor');
              const time = getCurrentTimeString();
              const html = `
                <div class="flex justify-end mb-3">
                  <div class="max-w-[80%] flex flex-col items-end">
                    <div class="user-bubble p-3 text-slate-900 text-xs shadow font-medium leading-relaxed">
                      ${escapeHtml(text)}
                    </div>
                    <span class="text-[9px] text-slate-700 mr-1 mt-0.5 inline-block">${time}</span>
                  </div>
                </div>
              `;
              anchor.insertAdjacentHTML('beforebegin', html);
              scrollToBottom();
            }

            function appendBotText(text) {
              const anchor = document.getElementById('chat-anchor');
              const time = getCurrentTimeString();
              const html = `
                <div class="flex items-start gap-2 mb-3">
                  <div class="w-7 h-7 rounded-full bg-white text-emerald-700 flex items-center justify-center text-xs shadow flex-shrink-0">
                    🌱
                  </div>
                  <div class="max-w-[85%]">
                    <div class="bot-bubble p-3 text-slate-800 text-xs shadow leading-relaxed">
                      ${escapeHtml(text)}
                    </div>
                    <span class="text-[9px] text-slate-700 ml-1 mt-0.5 inline-block">${time}</span>
                  </div>
                </div>
              `;
              anchor.insertAdjacentHTML('beforebegin', html);
              scrollToBottom();
            }

            function appendBotReply(data) {
              if (data.type === 'text') {
                appendBotText(data.text);
                return;
              }

              if (data.type === 'flex') {
                const contents = data.contents;
                renderFlexBubble(data.altText, contents);
                return;
              }

              appendBotText(JSON.stringify(data));
            }

            function renderFlexBubble(altText, bubble) {
              const anchor = document.getElementById('chat-anchor');
              const time = getCurrentTimeString();

              let headerBg = "#047857";
              let headerHtml = "";
              if (bubble.header) {
                headerBg = bubble.header.backgroundColor || "#047857";
                const items = bubble.header.contents || [];
                headerHtml = items.map(it => `
                  <div class="${it.size === 'xl' ? 'text-base font-bold text-white mt-1' : (it.size === 'lg' ? 'text-sm font-bold text-white mt-0.5' : 'text-[11px]')} " style="color: ${it.color || '#FFFFFF'}">
                    ${escapeHtml(it.text || '')}
                  </div>
                `).join('');
              }

              let bodyHtml = "";
              if (bubble.body) {
                const rows = bubble.body.contents || [];
                bodyHtml = rows.map(r => {
                  if (r.type === 'box' && r.contents && r.contents.length === 2) {
                    return `
                      <div class="flex items-start text-xs py-1 border-b border-slate-100 last:border-0">
                        <span class="w-20 text-slate-500 text-[11px] flex-shrink-0">${escapeHtml(r.contents[0].text || '')}</span>
                        <span class="flex-1 font-semibold text-slate-800 text-[11px]" style="color: ${r.contents[1].color || '#1e293b'}">${escapeHtml(r.contents[1].text || '')}</span>
                      </div>
                    `;
                  } else if (r.type === 'text') {
                    return `<div class="text-xs text-slate-700 my-1">${escapeHtml(r.text || '')}</div>`;
                  }
                  return '';
                }).join('');
              }

              let footerHtml = "";
              if (bubble.footer && bubble.footer.contents) {
                footerHtml = bubble.footer.contents.map(btn => {
                  const label = btn.action ? btn.action.label : '查看詳細';
                  const color = btn.color || '#059669';
                  return `
                    <button class="w-full py-2 px-3 rounded-lg text-white text-xs font-bold shadow-sm transition active:scale-95" style="background-color: ${color}">
                      ${escapeHtml(label)}
                    </button>
                  `;
                }).join('');
              }

              const html = `
                <div class="flex items-start gap-2 mb-3">
                  <div class="w-7 h-7 rounded-full bg-white text-emerald-700 flex items-center justify-center text-xs shadow flex-shrink-0">
                    🌱
                  </div>
                  <div class="max-w-[88%] w-full">
                    <div class="bot-bubble overflow-hidden shadow-lg border border-slate-200">
                      <!-- Card Header -->
                      <div class="p-3 text-white" style="background-color: ${headerBg}">
                        ${headerHtml}
                      </div>
                      <!-- Card Body -->
                      <div class="p-3 bg-white space-y-1">
                        ${bodyHtml}
                      </div>
                      <!-- Card Footer -->
                      ${footerHtml ? `<div class="p-2.5 bg-slate-50 border-t border-slate-100 space-y-1.5">${footerHtml}</div>` : ''}
                    </div>
                    <span class="text-[9px] text-slate-700 ml-1 mt-0.5 inline-block">${time}</span>
                  </div>
                </div>
              `;

              anchor.insertAdjacentHTML('beforebegin', html);
              scrollToBottom();
            }

            function getCurrentTimeString() {
              const d = new Date();
              return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            }

            function scrollToBottom() {
              const box = document.getElementById('chat-box');
              box.scrollTop = box.scrollHeight;
            }

            function escapeHtml(str) {
              if (!str) return '';
              return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            }
          </script>
        </body>
        </html>
        """;
    }
}
