module.exports = {
  ci: {
    collect: {
      url: [
        'https://nenotv.com/',
        'https://nenotv.com/pricing/',
        'https://nenotv.com/language/nl/',
        'https://nenotv.com/language/de/'
      ],
      numberOfRuns: 2,
      settings: { chromeFlags: '--no-sandbox --headless=new' }
    },
    assert: {
      assertions: {
        'categories:accessibility': ['warn', { minScore: 0.90 }],
        'categories:best-practices': ['warn', { minScore: 0.90 }],
        'categories:seo': ['warn', { minScore: 0.90 }],
        'first-contentful-paint': ['warn', { maxNumericValue: 3000 }],
        'largest-contentful-paint': ['warn', { maxNumericValue: 4000 }],
        'cumulative-layout-shift': ['warn', { maxNumericValue: 0.15 }]
      }
    },
    upload: { target: 'filesystem', outputDir: './results/lighthouse' }
  }
};
