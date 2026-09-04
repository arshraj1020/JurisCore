/** @type {import('tailwindcss').Config} */

/**
 * The visual vocabulary, in one file.
 *
 * `ink` is a blue-leaning neutral rather than a pure grey — it keeps the surfaces from
 * looking cold next to the brand hue, and it is what the whole interface is built from:
 * white cards, `ink-200` hairlines, `ink-500` secondary text.
 *
 * `brand` is a deliberately desaturated indigo. The obvious choice for "blue software" is
 * a vivid #3366ff, and it reads as a consumer product. Legal software is looked at for
 * eight hours a day by people who need the *status* colours to be the loudest thing on
 * screen, so the brand hue sits back and lets green, amber and red carry meaning.
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 500 and 600 are the two text greys, and both clear 4.5:1 against every
        // surface the interface puts them on — white, `ink-50`, `ink-100` and `brand-50`.
        // 400 and below are for icons, hairlines and dividers, never for reading.
        ink: {
          50: '#f7f8fa', 100: '#eef0f4', 200: '#dde1e8', 300: '#c2c8d4',
          400: '#98a1b4', 500: '#626c81', 600: '#4b5468', 700: '#3d4557',
          800: '#2f3644', 900: '#232936', 950: '#161a23',
        },
        brand: {
          50: '#f0f3fb', 100: '#e0e6f6', 200: '#c5d0ed', 300: '#9db0df',
          400: '#7089cd', 500: '#4f68b8', 600: '#3d519b', 700: '#33427e',
          800: '#2d3968', 900: '#293357', 950: '#1a2038',
        },
      },
      fontFamily: {
        sans: [
          'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto',
          'Helvetica Neue', 'Arial', 'sans-serif',
        ],
        mono: [
          'ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'Liberation Mono',
          'monospace',
        ],
      },
      fontSize: {
        // A 13px step for dense table and metadata text, which 14px makes too heavy.
        xs: ['0.75rem', { lineHeight: '1.05rem' }],
        '2xs': ['0.6875rem', { lineHeight: '0.95rem', letterSpacing: '0.02em' }],
        sm: ['0.8125rem', { lineHeight: '1.25rem' }],
        base: ['0.875rem', { lineHeight: '1.375rem' }],
      },
      boxShadow: {
        // One card shadow, barely there. Depth is carried by hairlines, not drop shadows.
        card: '0 1px 2px 0 rgb(22 26 35 / 0.04)',
        raised: '0 1px 3px 0 rgb(22 26 35 / 0.08), 0 1px 2px -1px rgb(22 26 35 / 0.06)',
        pop: '0 8px 24px -6px rgb(22 26 35 / 0.16), 0 2px 6px -2px rgb(22 26 35 / 0.08)',
      },
      borderRadius: {
        // Nothing rounder than this anywhere: pills are for badges only.
        md: '0.3125rem',
        lg: '0.4375rem',
      },
      keyframes: {
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 120ms ease-out',
        'slide-up': 'slide-up 140ms ease-out',
      },
    },
  },
  plugins: [],
};
