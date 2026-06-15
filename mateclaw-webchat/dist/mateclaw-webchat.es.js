const E = {
  position: "bottom-right",
  primaryColor: "var(--mc-primary, #D97757)",
  title: "化帆AI",
  placeholder: "Type a message..."
};
let n, i, b, a = [], l = !1, p = !1;
function S(e) {
  n = { ...E, ...e }, b = localStorage.getItem("mc-webchat-visitor") || T(), localStorage.setItem("mc-webchat-visitor", b), $(), D();
}
function T() {
  return "v_" + Math.random().toString(36).substring(2, 10) + Date.now().toString(36);
}
function $() {
  const e = document.createElement("style");
  e.textContent = `
    .mc-webchat-bubble,
    .mc-webchat-panel,
    .mc-webchat-panel * {
      box-sizing: border-box;
      font-family: var(--mc-font-body, 'Inter', 'Avenir Next', 'SF Pro Display', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif);
      letter-spacing: 0;
    }
    .mc-webchat-bubble {
      position: fixed;
      ${n.position === "bottom-left" ? "left: 20px" : "right: 20px"};
      bottom: 20px;
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: ${n.primaryColor};
      color: var(--mc-text-inverse, #ffffff);
      border: none;
      cursor: pointer;
      box-shadow: var(--mc-shadow-medium, 0 18px 48px rgba(58, 32, 19, 0.12));
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 99999;
      transition: transform 0.2s;
    }
    .mc-webchat-bubble:hover { transform: scale(1.1); }
    .mc-webchat-panel {
      position: fixed;
      ${n.position === "bottom-left" ? "left: 20px" : "right: 20px"};
      bottom: 88px;
      width: 380px;
      height: 520px;
      background: var(--mc-bg-elevated, #ffffff);
      color: var(--mc-text-primary, #1d1612);
      border: 1px solid var(--mc-border-light, #ebe3db);
      border-radius: var(--mc-radius-md, 12px);
      box-shadow: var(--mc-shadow-strong, 0 24px 70px rgba(58, 32, 19, 0.16));
      display: flex;
      flex-direction: column;
      z-index: 99999;
      overflow: hidden;
    }
    .mc-webchat-header {
      padding: 14px 16px;
      background: var(--mc-chat-header-bg, #ffffff);
      color: var(--mc-text-primary, #1d1612);
      font-weight: 600;
      font-size: 15px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid var(--mc-border-light, #ebe3db);
    }
    .mc-webchat-close {
      background: none;
      border: none;
      color: var(--mc-text-secondary, #665245);
      cursor: pointer;
      font-size: 18px;
      padding: 0 4px;
      opacity: 0.8;
    }
    .mc-webchat-close:hover { opacity: 1; }
    .mc-webchat-messages {
      flex: 1;
      overflow-y: auto;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      background: var(--mc-chat-bg, #FAFAF8);
    }
    .mc-webchat-msg {
      max-width: 85%;
      padding: 8px 12px;
      border-radius: var(--mc-radius-md, 12px);
      font-size: var(--mc-text-sm, 13px);
      line-height: 1.5;
      word-break: break-word;
      white-space: pre-wrap;
    }
    .mc-webchat-msg--user {
      align-self: flex-end;
      background: var(--mc-user-bubble-bg, ${n.primaryColor});
      color: var(--mc-user-bubble-color, #ffffff);
      border-bottom-right-radius: 4px;
    }
    .mc-webchat-msg--assistant {
      align-self: flex-start;
      background: var(--mc-assistant-bubble-bg, #ffffff);
      color: var(--mc-assistant-bubble-color, #1C1410);
      border: 1px solid var(--mc-assistant-bubble-border, #DDD5CC);
      border-bottom-left-radius: 4px;
    }
    .mc-webchat-input-area {
      padding: 10px 12px;
      border-top: 1px solid var(--mc-border-light, #ebe3db);
      display: flex;
      gap: 8px;
      background: var(--mc-bg-elevated, #ffffff);
    }
    .mc-webchat-input {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid var(--mc-input-border, #DDD5CC);
      border-radius: var(--mc-radius-full, 9999px);
      background: var(--mc-input-bg, #FAFAF8);
      color: var(--mc-input-text, #1C1410);
      font-size: var(--mc-text-sm, 13px);
      outline: none;
    }
    .mc-webchat-input:focus { border-color: ${n.primaryColor}; }
    .mc-webchat-send {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: ${n.primaryColor};
      color: var(--mc-text-inverse, #ffffff);
      border: none;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .mc-webchat-send:disabled { opacity: 0.5; cursor: not-allowed; }
    @media (max-width: 480px) {
      .mc-webchat-panel { width: calc(100vw - 24px); left: 12px; right: 12px; bottom: 80px; height: 60vh; }
    }
  `, document.head.appendChild(e);
}
function D() {
  i = document.createElement("div"), i.id = "mc-webchat-root";
  const e = document.createElement("button");
  e.className = "mc-webchat-bubble", e.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>', e.onclick = () => v(), i.appendChild(e), document.body.appendChild(i);
}
function v() {
  l = !l;
  const e = i.querySelector(".mc-webchat-panel");
  l && !e ? I() : !l && e && e.remove();
}
function I() {
  const e = document.createElement("div");
  e.className = "mc-webchat-panel", e.innerHTML = `
    <div class="mc-webchat-header">
      <span>${n.title}</span>
      <button class="mc-webchat-close">&times;</button>
    </div>
    <div class="mc-webchat-messages" id="mc-messages"></div>
    <div class="mc-webchat-input-area">
      <input class="mc-webchat-input" placeholder="${n.placeholder}" id="mc-input" />
      <button class="mc-webchat-send" id="mc-send">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
      </button>
    </div>
  `, e.querySelector(".mc-webchat-close").addEventListener("click", v);
  const t = e.querySelector("#mc-input"), r = e.querySelector("#mc-send");
  t.addEventListener("keydown", (o) => {
    o.key === "Enter" && !o.shiftKey && (o.preventDefault(), w(t.value));
  }), r.addEventListener("click", () => w(t.value)), i.appendChild(e), M();
}
function M() {
  const e = document.getElementById("mc-messages");
  e && (e.innerHTML = "", a.forEach((t) => {
    const r = document.createElement("div");
    r.className = `mc-webchat-msg mc-webchat-msg--${t.role}`, r.textContent = t.content, e.appendChild(r);
  }), e.scrollTop = e.scrollHeight);
}
function g(e, t) {
  a.push({ role: e, content: t });
  const r = document.getElementById("mc-messages");
  if (!r) return;
  const o = document.createElement("div");
  return o.className = `mc-webchat-msg mc-webchat-msg--${e}`, o.textContent = t, r.appendChild(o), r.scrollTop = r.scrollHeight, o;
}
function x(e) {
  const t = document.getElementById("mc-messages");
  if (!t) return;
  const r = t.querySelector(".mc-webchat-msg--assistant:last-child");
  r && (r.textContent = e, t.scrollTop = t.scrollHeight);
}
async function w(e) {
  var o;
  if (e = e.trim(), !e || p) return;
  const t = document.getElementById("mc-input");
  t && (t.value = ""), g("user", e), p = !0, g("assistant", "...");
  let r = "";
  try {
    const c = await fetch(`${n.server}/api/v1/channels/webchat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-MC-Key": n.apiKey,
        Accept: "text/event-stream"
      },
      body: JSON.stringify({ message: e, visitorId: b })
    });
    if (!c.ok)
      throw new Error(`HTTP ${c.status}`);
    const f = (o = c.body) == null ? void 0 : o.getReader(), y = new TextDecoder();
    if (!f) throw new Error("No response body");
    let d = "";
    for (; ; ) {
      const { done: C, value: k } = await f.read();
      if (C) break;
      d += y.decode(k, { stream: !0 });
      const h = d.split(`
`);
      d = h.pop() || "";
      for (const s of h)
        if (s.startsWith("data:")) {
          const m = s.slice(5).trim();
          if (!m) continue;
          try {
            const u = JSON.parse(m);
            u.text && (r += u.text, x(r));
          } catch {
          }
        } else if (s.startsWith("event:") && s.slice(6).trim() === "done")
          break;
    }
    a.length > 0 && (a[a.length - 1].content = r || "(no response)");
  } catch (c) {
    x(`Error: ${c.message}`), a.length > 0 && (a[a.length - 1].content = `Error: ${c.message}`);
  } finally {
    p = !1;
  }
}
typeof window < "u" && (window.MateClawWebChat = { init: S });
export {
  S as init
};
