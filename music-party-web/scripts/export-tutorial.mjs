import { mkdir, writeFile } from 'node:fs/promises';
import process from 'node:process';
import { log } from 'node:console';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TUTORIAL_UI, TUTORIAL_STEPS, TUTORIAL_TOPICS } from '../src/constants/tutorial.js';

export function tutorialText() {
    const lines = ['MusicParty 新手教程｜完整界面文案', '', '一、快速入门', ''];
    for (const [index, step] of TUTORIAL_STEPS.entries()) {
        lines.push(`${index + 1}. ${step.title}`, ...step.body.map((text) => `  ${text}`));
        if (step.mobileBody) lines.push('  手机提示：', ...step.mobileBody.map((text) => `  ${text}`));
        lines.push('');
    }
    lines.push('二、完整教程', '');
    for (const [index, topic] of TUTORIAL_TOPICS.entries()) {
        lines.push(`${index + 1}. ${topic.title}`, topic.summary, '');
        for (const card of topic.cards) lines.push(`【${card.title}】`, ...card.body, '');
    }
    lines.push('三、界面标题、按钮与提示', '');
    lines.push(...Object.values(TUTORIAL_UI).map((text) => `• ${text}`));
    lines.push(
        '',
        '说明：引导中的步骤序号、总数和主题标题均来自上述同一份文案。具体房间规则以当前界面与管理员设置为准。',
        ''
    );
    return lines.join('\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const destination = resolve(process.argv[2] || '../docs/新手教程全部文案.txt');
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, '\uFEFF' + tutorialText(), 'utf8');
    log(destination);
}
