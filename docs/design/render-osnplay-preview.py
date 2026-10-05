#!/usr/bin/env python3
"""Export the offline concept and render/check it in locally installed Chrome.

Requires Playwright's Python package and Pillow; no browser download is needed.
"""
import argparse
import base64
from pathlib import Path
from PIL import Image
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser()
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--chrome', default='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
args = parser.parse_args()
assert args.output_dir.is_dir(), 'Choose an existing output directory'
output = args.output_dir.resolve()
design = Path(__file__).resolve().parent
root = design.parents[1]
icon_data = base64.b64encode((root / 'asset/osnplay/osnplay-launcher-preview.png').read_bytes()).decode()
source = (design / 'osnplay-ui-v1.html').read_text().replace('__OSNPLAY_ICON__', 'data:image/png;base64,' + icon_data)
html = output / 'OsnPlay-UI-设计预览-V1.html'
html.write_text(source)

with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=args.chrome, headless=True)
    context = browser.new_context(viewport={'width': 1600, 'height': 1030}, device_scale_factor=4 / 3,
                                  color_scheme='light', reduced_motion='reduce')
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('requestfailed', lambda request: errors.append(f'Request failed: {request.url}'))
    screenshots = []
    for view, label in [('home', '主页'), ('connection', '连接'), ('settings', '设置')]:
        pair = []
        for theme, theme_label in [('light', '浅色'), ('dark', '深色')]:
            page.emulate_media(color_scheme=theme)
            page.goto(html.as_uri() + f'?page={view}&theme=system')
            page.wait_for_function('Array.from(document.images).every(image => image.complete && image.naturalWidth > 0)')
            assert page.locator('body').get_attribute('data-theme') == theme
            assert page.locator(f'.sidebar [data-page="{view}"]').get_attribute('aria-current') == 'page'
            assert page.locator('#main').evaluate('(element) => element.scrollWidth <= element.clientWidth')
            assert page.locator('.sidebar').evaluate('(element) => element.scrollWidth <= element.clientWidth')
            assert page.locator('.app button').evaluate_all('(buttons) => buttons.every(button => button.getBoundingClientRect().height >= 48)')
            if view == 'settings':
                assert page.locator('[data-apply]').evaluate('(button) => button.getBoundingClientRect().bottom <= document.querySelector("#main").getBoundingClientRect().bottom')
            image = output / f'OsnPlay-UI-V1-{label}-{theme_label}.png'
            page.locator('.app').screenshot(path=str(image), animations='disabled')
            assert Image.open(image).size == (1920, 1080)
            pair.append(image)
        screenshots.append((label, pair))

    # System theme switches live; a manual override remains stable across OS changes.
    page.goto(html.as_uri())
    page.select_option('#theme-picker', 'system')
    page.emulate_media(color_scheme='dark')
    page.wait_for_function('document.body.dataset.theme === "dark"')
    page.emulate_media(color_scheme='light')
    page.wait_for_function('document.body.dataset.theme === "light"')
    page.select_option('#theme-picker', 'dark')
    page.emulate_media(color_scheme='light')
    assert page.locator('body').get_attribute('data-theme') == 'dark'
    page.reload()
    assert page.locator('#theme-picker').input_value() == 'dark'
    page.select_option('#theme-picker', 'system')
    page.locator('.sidebar [data-page="settings"]').click()
    assert page.get_by_role('heading', name='界面主题', exact=True).is_visible()
    page.locator('[data-theme-mode="light"]').click()
    assert page.locator('#theme-picker').input_value() == 'light'
    for category in ['display', 'audio', 'connection', 'diagnostics']:
        page.locator(f'[data-category="{category}"]').click()
        assert page.locator('.settings-card').is_visible()
    page.locator('.sidebar [data-page="connection"]').click()
    page.locator('[data-transport="usb"]').click()
    assert page.get_by_role('heading', name='USB 连接准备', exact=True).is_visible()
    page.locator('.sidebar [data-page="home"]').click()
    page.locator('[data-connect]').click()
    page.wait_for_function('document.querySelector("#state-picker").value === "connected"')
    page.locator('[data-disconnect]').click()
    assert page.locator('#state-picker').input_value() == 'ready'
    page.select_option('#state-picker', 'failed')
    assert page.get_by_role('heading', name='热点连接未完成', exact=True).is_visible()
    assert not errors, '\n'.join(errors)

    # Verify the adaptive preview remains horizontally usable at a smaller size.
    page.set_viewport_size({'width': 1024, 'height': 768})
    for view in ['home', 'connection', 'settings']:
        page.locator(f'.sidebar [data-page="{view}"]').click()
        assert page.locator('.app').evaluate('(element) => element.scrollWidth <= element.clientWidth')
        assert page.locator('#main').evaluate('(element) => element.scrollWidth <= element.clientWidth')
    context.close()

    # One contact sheet for a fast comparison of the three pages and both themes.
    figures = []
    for label, pair in screenshots:
        for image, theme_label in zip(pair, ['浅色', '深色']):
            data = base64.b64encode(image.read_bytes()).decode()
            figures.append(f'<figure><figcaption>{label} <span>{theme_label}</span></figcaption><img src="data:image/png;base64,{data}"></figure>')
    board = browser.new_page(viewport={'width': 1552, 'height': 1580}, device_scale_factor=1)
    board.set_content('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
      *{box-sizing:border-box}body{margin:0;background:#e9eee9;color:#17251d;font-family:-apple-system,"PingFang SC",sans-serif;padding:34px}
      header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px}h1{font-size:31px;margin:0 0 9px;font-weight:650;letter-spacing:-.7px}p{font-size:15px;color:#53685a;margin:0}.tag{padding:10px 16px;background:#d4e8d8;color:#26653b;border-radius:20px;font-size:14px}
      .grid{display:grid;grid-template-columns:1fr 1fr;gap:26px}figure{margin:0;min-width:0}figcaption{font-size:19px;font-weight:600;margin:0 0 12px 4px;display:flex;align-items:center;gap:12px}figcaption span{font-size:13px;font-weight:400;color:#53685a}img{display:block;width:100%;border-radius:14px;border:1px solid #cedace}footer{margin-top:24px;font-size:13px;color:#53685a;line-height:1.7}
      </style><body><header><div><h1>OsnPlay / 车机界面设计 V1</h1><p>主页 · 连接 · 设置 / CarPlay 绿 / 默认跟随系统深浅色模式</p></div><span class="tag">1920 × 1080 横屏方案</span></header><div class="grid">'''
      + ''.join(figures) + '</div><footer>设计稿使用示例设备与连接状态。配套 HTML 可点击切换主题、页面和连接状态。</footer></body></html>')
    board.wait_for_function('Array.from(document.images).every(image => image.complete && image.naturalWidth > 0)')
    board.screenshot(path=str(output / 'OsnPlay-UI-设计总览-V1.png'), full_page=True)
    browser.close()

print(f'Interactive preview: {html}')
print(f'Contact sheet: {output / "OsnPlay-UI-设计总览-V1.png"}')
print('Verified: six 1920x1080 views, live system theme, persisted override, navigation, settings categories, USB, connection states and smaller-screen layout.')
