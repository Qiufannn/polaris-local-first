// Runs only in the authenticated, same-origin, asset-free browser bridge page.
// Cookies belong to the browser; this function never reads or returns credentials.
globalThis.__polarisHomeRequest = async function (key, body) {
  const fail = error => { globalThis[key] = { done: true, error }; };
  try {
    const response = await fetch('/api/home/rpc', {
      method: 'POST', credentials: 'same-origin', redirect: 'error', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-Home-Action': '1' }, body
    });
    if (response.status === 401) return fail('原小屋需要完成网页登录，请使用“登录原小屋”后再测试。');
    if (response.status === 403) return fail('原小屋拒绝当前 App 浏览器会话（HTTP 403），已停止请求；若网页登录后仍如此，请报告这个状态，不必反复登录。');
    if (response.status === 404) return fail('原小屋尚未提供浏览器连接接口（HTTP 404），请核对原站后台版本。');
    if (!response.ok) return fail('原小屋请求未完成（HTTP ' + response.status + '）。写入结果不明时先读回核对，不要重复提交。');
    if (response.status === 202 || response.status === 204) { globalThis[key] = { done: true, status: response.status, data: '' }; return; }
    if (!(response.headers.get('Content-Type') || '').toLowerCase().includes('application/json')) return fail('原小屋返回了登录页或其他非 JSON 内容，未启用连接。');
    if (Number(response.headers.get('Content-Length')) > 2 * 1024 * 1024) return fail('原小屋响应过大，已停止读取。');
    const data = await response.text();
    if (data.length > 2 * 1024 * 1024) return fail('原小屋响应过大，已停止读取。');
    const parsed = JSON.parse(data);
    if (!parsed || parsed.jsonrpc !== '2.0') return fail('原小屋响应协议不正确，未启用连接。');
    globalThis[key] = { done: true, status: response.status, data };
  } catch {
    fail('原小屋浏览器请求未完成，可能需要网页登录或检查网络。写入结果不明时先读回核对，不要重复提交。');
  }
};
