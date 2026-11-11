// @ts-check
const eslint = require("@eslint/js");
const { defineConfig } = require("eslint/config");
const tseslint = require("typescript-eslint");
const angular = require("angular-eslint");
// Runs Prettier as an ESLint rule, so `ng lint` reports formatting issues and `ng lint --fix`
// formats the code - no separate prettier command needed.
const prettierRecommended = require("eslint-plugin-prettier/recommended");

module.exports = defineConfig([
  {
    // index.html is a plain HTML document, not an Angular template - the template parser
    // used for **/*.html below chokes on its doctype.
    ignores: ["dist/**", ".angular/**", "node_modules/**", "src/index.html"],
  },
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
      prettierRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      "@angular-eslint/directive-selector": [
        "error",
        {
          type: "attribute",
          prefix: "app",
          style: "camelCase",
        },
      ],
      "@angular-eslint/component-selector": [
        "error",
        {
          type: "element",
          prefix: "app",
          style: "kebab-case",
        },
      ],
    },
  },
  {
    files: ["**/*.html"],
    extends: [
      angular.configs.templateRecommended,
      angular.configs.templateAccessibility,
      prettierRecommended,
    ],
    rules: {
      // Without an explicit parser, eslint-plugin-prettier infers a JS parser for template files
      // and "formats" them as JSX - it turned app.html's `<router-outlet />` into
      // `<router-outlet />;`, i.e. a stray semicolon rendered as text in the app.
      "prettier/prettier": ["error", {parser: "angular"}],
      // Every visible word comes from a translation key (docs/architecture/i18n.md) - static text with letters in a
      // template is refused. Icon names, code and attributes nobody reads are left out.
      "@angular-eslint/template/i18n": [
        "error",
        {
          checkId: false,
          checkText: true,
          checkAttributes: true,
          ignoreTags: ["mat-icon", "code", "pre"],
          // Attributes that are no text for the reader: Material's and the app's settings, table columns, icon names.
          ignoreAttributes: [
            "accept",
            "align",
            "appearance",
            "aria-orientation",
            "backLink",
            "form",
            "icon",
            "matColumnDef",
            "matTooltipPosition",
            "mode",
            // <video preload="metadata">: lint:fix would otherwise mark it i18n-preload, which compiles to $localize -
            // not in the app - and threw the first time a clip was shown.
            "preload",
            "preserveAspectRatio",
            // <iframe sandbox="..." referrerpolicy="..."> of a handbook's website: settings, compiled to $localize otherwise.
            "referrerpolicy",
            "sandbox",
            "scope",
            "subscriptSizing",
            "xPosition",
            "yPosition",
          ],
        },
      ],
    },
  },
]);
