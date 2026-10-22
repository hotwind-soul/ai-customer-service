const fs = require('fs');
const vm = require('vm');
const { compile } = require('@vue/compiler-dom');

const files = process.argv.slice(2);
let bad = 0;
for (const file of files) {
  if (!fs.existsSync(file)) {
    console.error(file, '不存在');
    bad++;
    continue;
  }
  const html = fs.readFileSync(file, 'utf8');
  let ok = true;
  const scriptPattern = /<script(?![^>]*src=)[^>]*>([\s\S]*?)<\/script>/g;
  let match;
  let scriptCount = 0;
  while ((match = scriptPattern.exec(html))) {
    scriptCount++;
    try {
      new vm.Script(match[1], {filename:`${file}#inline${scriptCount}`});
    } catch (error) {
      ok = false;
      bad++;
      console.error(`✗ ${file} 内联脚本#${scriptCount} 语法错误: ${error.message}`);
    }
  }

  const bodyMatch = html.match(/<body[^>]*>([\s\S]*?)<\/body>/);
  if (!bodyMatch) {
    ok = false;
    bad++;
    console.error(`✗ ${file} 缺少 body`);
  } else {
    const body = bodyMatch[1]
      .replace(/<script[\s\S]*?<\/script>/g, '')
      .replace(/<style[\s\S]*?<\/style>/g, '');
    const originalWarn = console.warn;
    console.warn = message => {
      if (!String(message).includes('<tr> cannot be child')) originalWarn(message);
    };
    try {
      compile(body);
    } catch (error) {
      ok = false;
      bad++;
      console.error(`✗ ${file} 模板编译错误: ${error.message}`);
    } finally {
      console.warn = originalWarn;
    }
  }
  if (ok) console.log(`✓ ${file} OK（${scriptCount} 个内联脚本 + 模板）`);
}

process.exit(bad ? 1 : 0);
