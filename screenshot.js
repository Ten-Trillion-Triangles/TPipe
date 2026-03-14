const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1280, height: 800 },
    colorScheme: 'dark'
  });

  // Wait until network is somewhat idle
  await page.goto('http://localhost:8081', { waitUntil: 'networkidle' });

  // Wait for auth overlay
  await page.waitForSelector('#authOverlay', { state: 'visible' });

  // Type password
  await page.fill('#authKey', 'demo123');

  // Click connect
  await page.click('.auth-btn');

  // Wait for auth overlay to hide
  await page.waitForSelector('#authOverlay', { state: 'hidden' });

  // Take screenshot of list loaded
  await page.waitForTimeout(2000);
  await page.screenshot({ path: 'dashboard_list.png' });

  // Click the first trace item to load it into the iframe
  try {
     const elements = await page.$$('.trace-item');
     if (elements.length > 0) {
         await elements[0].click();
         // Wait a moment for iframe to render
         await page.waitForTimeout(1000);
         // Take screenshot
         await page.screenshot({ path: 'dashboard_demo.png' });
     }
  } catch(e) {
     console.error("Error clicking item", e);
  }

  await browser.close();
  console.log("Screenshots saved to dashboard_list.png and dashboard_demo.png");
})();
