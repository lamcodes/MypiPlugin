// Pi RPC 协议探查脚本 —— 为 IDEA 插件的解析层钉死契约
//
// 作用:
//   1. 拉起 `pi --mode rpc`,做一次最小往返(发一条 prompt,收流式回复);
//   2. 把 pi stdout 的每一帧 JSONL 原样落盘到 pi-trace.jsonl,作为 Kotlin 解析层的测试夹具;
//   3. 在终端打印关键事件类型,帮助你确认协议真实行为(文档可能滞后于版本)。
//
// 运行:
//   node pi-probe.mjs
//
// 前置:
//   - 已安装 Pi:  npm install -g --ignore-scripts @earendil-works/pi-coding-agent
//   - 想看到真正的流式回复,需先配好 Pi 的 LLM provider(如 ANTHROPIC_API_KEY)。
//     即使没配,也能抓到 ready / response 帧验证握手。
//
// 关键陷阱(都在脚本里处理了,改动时别破坏):
//   - Windows 上 npm 全局 bin 是 pi.cmd,spawn 必须带 shell:true 才能找到;
//   - JSONL 只能按 \n 切分,strip 尾部 \r —— 绝不能用 node 的 readline,
//     它会把 U+2028 / U+2029 也当换行,直接破坏 JSON 字符串(协议文档明确点名)。

import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';

// 探查用的提示词。这里就是未来 IDEA 插件"选区→prompt"逻辑的预演:
// 真实插件里,这一段会带上下文(类名、行号、选中代码)。
const PROMPT = '用一句话介绍你自己,不超过 30 个字。';

// 把每一帧原样留存,作为 Kotlin 解析层的基准夹具
const trace = createWriteStream('pi-trace.jsonl');

// Windows: pi 是 pi.cmd,必须走 shell 才找得到
const pi = spawn('pi', ['--mode', 'rpc', '--no-session'], { shell: true });

let buffer = '';       // stdout 半包缓冲(JSONL 帧可能跨 chunk 到达)
let sent = false;      // prompt 是否已发(ready 触发或兜底定时器,先到先发)
let gotText = false;

// 60s 兜底,避免卡死;超时也会保留已抓到的帧
const hardTimeout = setTimeout(() => {
  console.error('\n[probe] 60s 超时,强制退出(已抓帧仍保留在 pi-trace.jsonl)');
  cleanup(1);
}, 60000);

// 3s 内没收到 ready 帧,就直接发 prompt(兼容不发 ready 的旧版本)
const readyFallback = setTimeout(() => sendPrompt(), 3000);

function cleanup(code = 0) {
  clearTimeout(hardTimeout);
  clearTimeout(readyFallback);
  trace.end();
  try { pi.stdin.end(); } catch { /* 已关闭则忽略 */ }
  setTimeout(() => process.exit(code), 300);
}

// 严格 JSONL 切分:只认 \n,strip 尾部 \r。不要换成 readline。
function feed(chunk) {
  buffer += chunk.toString('utf8');
  let nl;
  while ((nl = buffer.indexOf('\n')) !== -1) {
    let line = buffer.slice(0, nl);
    buffer = buffer.slice(nl + 1);
    if (line.endsWith('\r')) line = line.slice(0, -1);
    if (line.length === 0) continue;
    handle(line);
  }
}

function handle(line) {
  trace.write(line + '\n'); // 原样留存
  let frame;
  try { frame = JSON.parse(line); }
  catch { console.error('[probe][非JSON行]', line); return; }

  switch (frame.type) {
    case 'ready':
      console.log('[probe] ready, protocolVersion =', frame.protocolVersion, '(maxFrameBytes =', frame.maxFrameBytes + ')');
      sendPrompt();
      break;
    case 'response':
      // prompt 命令的即时回执:success 仅代表"已被接收",真正的回答在后续事件流里
      console.log('[probe] response:', frame.command, 'success =', frame.success,
        frame.success ? '' : ('error = ' + frame.error));
      break;
    case 'message_update': {
      const e = frame.assistantMessageEvent;
      if (e && e.type === 'text_delta') { process.stdout.write(e.delta); gotText = true; }
      break;
    }
    case 'tool_execution_start':
      console.log('\n[probe] 工具调用:', frame.toolName, frame.args ?? frame.arguments);
      break;
    case 'agent_end':
    case 'agent_settled':
      console.log('\n[probe] 收到', frame.type, '→ 一次完整往返结束');
      if (!gotText) console.log('[probe] (未收到文本流——多半是没配 LLM provider,但握手帧已抓到)');
      cleanup(0);
      break;
    default:
      console.log('[probe][event]', frame.type); // 发现新事件类型时从这里暴露
  }
}

function sendPrompt() {
  if (sent) return;
  sent = true;
  console.log('[probe] → 发送 prompt:', PROMPT);
  pi.stdin.write(JSON.stringify({ id: 'probe-1', type: 'prompt', message: PROMPT }) + '\n');
}

pi.stdout.on('data', feed);
pi.stderr.on('data', d => process.stderr.write('[pi stderr] ' + d));
pi.on('error', e => {
  console.error('[probe] 拉起 pi 失败:', e.message);
  console.error('请先安装 Pi:  npm install -g --ignore-scripts @earendil-works/pi-coding-agent');
  cleanup(1);
});
pi.on('exit', (code, signal) => console.log('[probe] pi 进程退出 code =', code, 'signal =', signal));

console.log('[probe] 正在拉起 pi --mode rpc ...');
