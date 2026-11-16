// Export only the text/JSON/CSV blob link explicitly clicked in the approved home.
globalThis.__polarisHomeDownload = async function (key, url, origin) {
 const fail=error=>{globalThis[key]={ready:true,error};};
 try {
  const click=globalThis.__polarisDownloadClick;
  if(location.origin!==origin||new URL(url).origin!==origin||!url.startsWith('blob:')||!click||click.url!==url||Date.now()-click.at>30000) return fail('请重新点击小屋里准备好的下载链接。');
  const response=await fetch(url);if(!response.ok)return fail('文件没有准备好，请重新导出。');
  const blob=await response.blob(),mime=blob.type.toLowerCase().split(';')[0];
  if(!['text/plain','text/csv','application/json'].includes(mime))return fail('当前 App 导出支持 TXT、CSV 和 JSON。');
  if(blob.size>16*1024*1024)return fail('文件超过 16 MB，请按本书或月份分批导出。');
  const data=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]||'');reader.onerror=reject;reader.readAsDataURL(blob);});
  globalThis[key]={ready:true,mime,name:click.name,size:blob.size,length:data.length,data};
 } catch { fail('文件读取未完成，原书和账目仍保留，请重新导出。'); }
};
