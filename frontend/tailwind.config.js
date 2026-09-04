/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: {
          50: '#f6f7f9', 100: '#eceef2', 200: '#d5d9e2', 300: '#b0b8c9',
          400: '#8591ab', 500: '#657391', 600: '#505c78', 700: '#424b61',
          800: '#394052', 900: '#333947', 950: '#22262f',
        },
        brand: {
          50: '#eef4ff', 100: '#dae6ff', 200: '#bcd3ff', 300: '#8eb6ff',
          400: '#598eff', 500: '#3366ff', 600: '#1d47f5', 700: '#1636e1',
          800: '#182fb6', 900: '#1a2e8f', 950: '#151d57',
        },
      },
      fontFamily: {
        sans: ['ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
