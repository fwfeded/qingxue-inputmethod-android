// //! 仅内存状态机演示，不调用网络下载、不读取手机或本机资料。
const $ = (id) => document.getElementById(id);
let phase = 'idle', timer, progress = 0, confirmAction;
function stop() { clearInterval(timer); clearTimeout(timer); timer = null; }
function page(open) { $('about').hidden = open; $('updates').hidden = !open; $('title').textContent = open ? '应用更新' : '关于青学'; }
const content = {
  idle:['尚未检查','检查是否有新版本','只查询版本信息，不发送输入内容、录音或学习数据。','检查更新'],
  checking:['正在检查','获取版本信息','连接发布源，验证版本清单…','正在检查…'],
  new:['发现新版','0.25.0 可供体验','此处使用示例版本，当前尚无正式下载源。','下载更新 · 约 70 MB'],
  current:['检查完成','已是最新版本','没有适合当前渠道与手机的新版本。','再次检查'],
  offline:['未能连接','暂时无法检查更新','网络不可用或发布源无法访问。当前版本仍可正常使用。','重试'],
  downloading:['正在下载','下载更新','你可以取消下载，继续使用当前版本。','下载中…'],
  verifying:['正在核对','校验安装包','核对文件完整性、包名、签名与版本。','校验中…'],
  invalid:['未通过校验','未安装这个文件','安装包与可信版本信息不一致，已停止安装。','重新检查'],
  ready:['校验通过','可以安装了','将打开 Android 安装确认。模型、词库和学习数据会保留。','前往安装'],
  done:['演示完成','已更新至示例 0.25.0','这只是交互演示。真实手机需要系统报告安装成功后才会显示完成。','重新体验']
};
function render(next) {
  phase = next;
  const [tag, heading, description, action] = content[next];
  $('tag').textContent = tag; $('heading').textContent = heading;
  $('description').textContent = description; $('action').textContent = action;
  $('action').disabled = ['checking','downloading','verifying'].includes(next);
  $('notes').hidden = !['new','downloading','verifying','ready'].includes(next);
  $('progress').hidden = next !== 'downloading';
  $('secondary').hidden = !['downloading','ready'].includes(next);
  $('secondary').textContent = next === 'ready' ? '稍后安装' : '取消下载';
}
function check() {
  stop(); page(true); render('checking');
  timer = setTimeout(() => {
    const value = $('scenario').value;
    render(['offline','current'].includes(value) ? value : 'new');
  }, 500);
}
function dialog(title, text, action, label='继续') {
  $('dialog-title').textContent = title; $('dialog-text').textContent = text;
  $('confirm-dialog').textContent = label; confirmAction = action; $('dialog').showModal();
}
function download() {
  stop(); progress = 0; $('progress').value = 0; render('downloading');
  timer = setInterval(() => {
    progress += 10; $('progress').value = progress;
    $('description').textContent = `已下载 ${progress}% · 演示进度`;
    if (progress >= 100) {
      stop(); render('verifying');
      timer = setTimeout(() => render($('scenario').value === 'invalid' ? 'invalid' : 'ready'), 700);
    }
  }, 200);
}
$('open').onclick = () => page(true);
$('back').onclick = () => page(false);
$('action').onclick = () => {
  if (phase === 'new') {
    if ($('scenario').value === 'mobile' && $('wifi').checked) dialog('当前使用移动网络','本次下载约 70 MB。继续只对本次生效，不改变“仅在 Wi-Fi 下下载”设置。', download, '本次继续下载');
    else download();
  } else if (phase === 'ready') {
    dialog('Android 安装确认 · 演示','是否安装青学更新？\n真实手机可能先要求允许此来源安装应用。拒绝或取消会返回当前版本，不会删除模型与学习数据。', () => render('done'), '模拟安装成功');
  } else check();
};
$('secondary').onclick = () => { stop(); render(phase === 'ready' ? 'ready' : 'new'); if (phase === 'ready') page(false); };
$('channel').onchange = () => { stop(); render('idle'); };
$('scenario').onchange = () => { stop(); page(true); render('idle'); };
$('reset').onclick = () => { stop(); $('scenario').value='new'; $('auto').checked=false; $('wifi').checked=true; $('channel').value='beta'; render('idle'); page(false); };
$('cancel-dialog').onclick = () => { $('dialog').close(); confirmAction=null; };
$('confirm-dialog').onclick = () => { $('dialog').close(); const action=confirmAction; confirmAction=null; if(action) action(); };
$('dialog').addEventListener('cancel', () => { confirmAction=null; });
$('refs').onclick = () => dialog('参考与边界','NewPipe：自动检查开关与手动检查入口分层。\n青简：签名索引与渠道筛选思路。\n青学：沿用已确认设置风格。\n\n下载/安装均为模拟；未修改 Android 界面。完整来源链接见页面下方。', () => document.querySelector('.source').scrollIntoView({block:'center'}), '查看来源');
render('idle');
