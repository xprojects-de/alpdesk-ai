import hljs from "highlight.js/lib/core";
import apache from "highlight.js/lib/languages/apache";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import clojure from "highlight.js/lib/languages/clojure";
import cmake from "highlight.js/lib/languages/cmake";
import cpp from "highlight.js/lib/languages/cpp";
import csharp from "highlight.js/lib/languages/csharp";
import css from "highlight.js/lib/languages/css";
import dart from "highlight.js/lib/languages/dart";
import delphi from "highlight.js/lib/languages/delphi";
import diff from "highlight.js/lib/languages/diff";
import dockerfile from "highlight.js/lib/languages/dockerfile";
import dos from "highlight.js/lib/languages/dos";
import elixir from "highlight.js/lib/languages/elixir";
import erlang from "highlight.js/lib/languages/erlang";
import fortran from "highlight.js/lib/languages/fortran";
import fsharp from "highlight.js/lib/languages/fsharp";
import go from "highlight.js/lib/languages/go";
import gradle from "highlight.js/lib/languages/gradle";
import graphql from "highlight.js/lib/languages/graphql";
import groovy from "highlight.js/lib/languages/groovy";
import haskell from "highlight.js/lib/languages/haskell";
import http from "highlight.js/lib/languages/http";
import ini from "highlight.js/lib/languages/ini";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import julia from "highlight.js/lib/languages/julia";
import kotlin from "highlight.js/lib/languages/kotlin";
import latex from "highlight.js/lib/languages/latex";
import less from "highlight.js/lib/languages/less";
import lua from "highlight.js/lib/languages/lua";
import makefile from "highlight.js/lib/languages/makefile";
import markdown from "highlight.js/lib/languages/markdown";
import matlab from "highlight.js/lib/languages/matlab";
import nginx from "highlight.js/lib/languages/nginx";
import objectivec from "highlight.js/lib/languages/objectivec";
import perl from "highlight.js/lib/languages/perl";
import pgsql from "highlight.js/lib/languages/pgsql";
import php from "highlight.js/lib/languages/php";
import plaintext from "highlight.js/lib/languages/plaintext";
import powershell from "highlight.js/lib/languages/powershell";
import properties from "highlight.js/lib/languages/properties";
import protobuf from "highlight.js/lib/languages/protobuf";
import python from "highlight.js/lib/languages/python";
import r from "highlight.js/lib/languages/r";
import ruby from "highlight.js/lib/languages/ruby";
import rust from "highlight.js/lib/languages/rust";
import scala from "highlight.js/lib/languages/scala";
import scss from "highlight.js/lib/languages/scss";
import shell from "highlight.js/lib/languages/shell";
import sql from "highlight.js/lib/languages/sql";
import swift from "highlight.js/lib/languages/swift";
import typescript from "highlight.js/lib/languages/typescript";
import vbnet from "highlight.js/lib/languages/vbnet";
import vbscript from "highlight.js/lib/languages/vbscript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

// Only these languages go into the bundle - all of highlight.js would be close to a megabyte. Each brings its usual
// aliases (js, ts, py, sh, html, kt, yml, toml, ps1, ...).
const LANGUAGES = {
    apache,
    bash,
    c,
    clojure,
    cmake,
    cpp,
    csharp,
    css,
    dart,
    delphi,
    diff,
    dockerfile,
    dos,
    elixir,
    erlang,
    fortran,
    fsharp,
    go,
    gradle,
    graphql,
    groovy,
    haskell,
    http,
    ini,
    java,
    javascript,
    json,
    julia,
    kotlin,
    latex,
    less,
    lua,
    makefile,
    markdown,
    matlab,
    nginx,
    objectivec,
    perl,
    pgsql,
    php,
    plaintext,
    powershell,
    properties,
    protobuf,
    python,
    r,
    ruby,
    rust,
    scala,
    scss,
    shell,
    sql,
    swift,
    typescript,
    vbnet,
    vbscript,
    xml,
    yaml,
};
for (const [name, language] of Object.entries(LANGUAGES)) {
    hljs.registerLanguage(name, language);
}

export interface HighlightedCode {
    /** HTML of the code: escaped text in spans of highlight.js's classes. */
    html: string;
    /** The language's registered name, e.g. "typescript" for "ts". */
    language: string;
    /** The language's display name, e.g. "TypeScript". */
    name: string;
}

/**
 * The code highlighted in the language a model named for it - null for a language not known here, or none: guessing
 * the language is often wrong and changes while an answer streams in.
 */
export function highlightCode(code: string, lang: string): HighlightedCode | null {
    const definition = lang ? hljs.getLanguage(lang) : undefined;
    if (!definition) {
        return null;
    }
    const result = hljs.highlight(code, {language: lang, ignoreIllegals: true});
    return {html: result.value, language: result.language ?? lang, name: definition.name ?? lang};
}
