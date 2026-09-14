package com.ccj.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prompt the agent runs with, and the one setting that is about the prompt rather than the
 * project: which language it thinks and answers in.
 *
 * <p>The reasoning stream is model output like any other, so the only lever over it is what the prompt
 * asks for — and the lever is a weak one. Reasoning models pick the language of their thinking from
 * the whole context and often keep the language they started in, so what this reliably buys is
 * <em>answers</em> in the chosen language, plus thinking in it on the models that honour it: a relayed
 * deepseek-v4.1, measured here, keeps thinking in English with an English prompt, with a Chinese one
 * ("你必须用中文思考"), and with a Chinese user message, while a newer model follows the same prompt.
 * The sentences below therefore ask twice — once in English where the other rules live, once in the
 * target language, which is the stronger cue of the two — and the settings hint says plainly that the
 * answer always follows while the reasoning may not.
 */
public final class Prompts {

  /**
   * Kept deliberately short. Everything the model needs about behaviour that cannot be expressed as
   * a tool schema lives here; anything longer just burns context on every turn.
   */
  public static final String DEFAULT_SYSTEM =
      """
      You are ccj, a coding agent working directly in the user's shell.

      Rules:
      - Inspect before you change: read a file (or list the directory) before editing it.
      - Prefer exact edits over rewriting whole files, and keep the user's existing style.
      - Use bash for real commands and pipelines; use the search tools for locating code.
      - Never claim something works unless a command you ran proves it.
      - When the task is done, answer in prose: what changed, where, and how you verified it.

      When the project you are working in is ccj itself: build with
      `mvn -q -DskipTests -Djar.name=ccj-next package`, which writes target/ccj-next.jar without
      touching the jar this process is running from, then call `restart` with it. That installs the
      new jar and restarts on it, so the change takes effect with nobody doing it by hand. Never
      build plain `mvn package` from inside ccj: it truncates target/ccj.jar, and truncating the jar
      a JVM is executing kills that JVM in the middle of the build.
      """;

  /** "auto" means the prompt says nothing and the model answers in whatever language it likes. */
  public static final String AUTO = "auto";

  /**
   * One language the prompt can ask for: the value the settings form stores, the name to show, the
   * language's own name, and one sentence written *in* that language.
   *
   * <p>A name rather than a code, on purpose: models follow "Simplified Chinese" far more reliably
   * than "zh-CN". The native sentence is there because a model decides which language to think in
   * partly from the text in front of it — an English sentence asking for Chinese is a weak signal,
   * Chinese text asking for Chinese is a strong one, and the thinking stream is exactly where that
   * shows up first.
   */
  private record Language(String label, String nativeName, String nativeInstruction) {}

  private static final Map<String, Language> LANGUAGES = languages(
      new Language("简体中文", "简体中文", "请始终用简体中文思考和回答，即使用户使用的是其他语言。"),
      new Language("繁體中文", "繁體中文", "請始終用繁體中文思考和回答，即使用戶使用其他語言。"),
      new Language("English", "English", "Always think and answer in English."),
      new Language("日本語", "日本語", "ユーザーがどの言語で書いても、常に日本語で考え、日本語で答えてください。"),
      new Language("한국어", "한국어", "사용자가 어떤 언어로 쓰든 항상 한국어로 생각하고 한국어로 답하세요."),
      new Language("русский", "русский", "Всегда думай и отвечай по-русски, на каком бы языке ни писал пользователь."),
      new Language("español", "español", "Piensa y responde siempre en español, sea cual sea el idioma del usuario."),
      new Language("français", "français", "Pense toujours et réponds en français, quelle que soit la langue de l'utilisateur."),
      new Language("Deutsch", "Deutsch", "Denke und antworte immer auf Deutsch, egal in welcher Sprache der Nutzer schreibt."),
      new Language("português", "português", "Pense e responda sempre em português, seja qual for o idioma do usuário."));

  /**
   * The value the settings form stores, which is the English name — a form is read by the person, and
   * the record's order is the order it is shown in.
   */
  private static Map<String, Language> languages(Language... offered) {
    String[] values = {
      "Simplified Chinese",
      "Traditional Chinese",
      "English",
      "Japanese",
      "Korean",
      "Russian",
      "Spanish",
      "French",
      "German",
      "Portuguese"
    };
    Map<String, Language> map = new LinkedHashMap<>();
    for (int i = 0; i < offered.length; i++) {
      map.put(values[i], offered[i]);
    }
    return map;
  }

  private Prompts() {}

  /** Every language a prompt can ask for, as {@code value} strings. */
  public static List<String> languageValues() {
    return List.copyOf(LANGUAGES.keySet());
  }

  /** The offered languages as {@code {value, label}} pairs, for the settings form. */
  public static List<String[]> languageChoices() {
    List<String[]> choices = new ArrayList<>();
    LANGUAGES.forEach((value, language) -> choices.add(new String[] {value, language.label()}));
    return choices;
  }

  /**
   * The prompt for a configured base prompt and language.
   *
   * @param system the prompt in effect, or null/blank for {@link #DEFAULT_SYSTEM}
   * @param language {@code auto} (or null/blank) to say nothing about language, otherwise one of
   *     {@link #languageValues()}
   */
  public static String system(String system, String language) {
    return system(system, language, null);
  }

  /**
   * The prompt for a configured base prompt, language, and the project the agent is working in.
   *
   * <p>The parts sit in a deliberate order: the project's own rules first, then the built-in rules,
   * then the language. The project's file comes <em>first</em> because it is the specific statement
   * about this work — the build command, the module not to touch — and a reader meeting general
   * instructions first has to hold them while being told the real ones. The built-in rules stay in
   * every prompt rather than being replaced by the file: they are not project preferences but the way
   * this agent works ("inspect before you change", "never claim something works unless a command
   * proved it"), and a project that adds its own rules has not asked to stop being told those. The
   * language goes last of all because it is about the shape of the reply rather than about the work,
   * and it is the instruction that most needs to survive being read last.
   *
   * @param workingDirectory where the agent will run, whose {@link ProjectPrompt} rules are prepended,
   *     or null for none
   */
  public static String system(String system, String language, Path workingDirectory) {
    String base = system == null || system.isBlank() ? DEFAULT_SYSTEM : system;
    String project = ProjectPrompt.from(workingDirectory);
    String withProject = project.isEmpty() ? base : project + "\n\n" + base;
    String asked = language == null ? "" : language.strip();
    if (asked.isEmpty() || AUTO.equalsIgnoreCase(asked)) {
      return withProject;
    }
    Language known = LANGUAGES.get(asked);
    if (known == null) {
      // A language this build does not list: ask for it in English, by the name that was given. The
      // form is a convenience, not a gate on what somebody may think in.
      return withProject + "\n\n" + instruction(asked, null);
    }
    return withProject + "\n\n" + instruction(known.nativeName(), known.nativeInstruction());
  }

  /**
   * The sentence itself, as its own method so a test can pin the wording of what the model is told
   * without repeating it here.
   *
   * <p>Two parts on purpose. The first states the rule in English, which is where the agent's other
   * rules live and what a model reads as an instruction. The second says the same thing in the target
   * language, because that is what actually sets the language of the text the model writes next —
   * including its thinking, which is the stream this exists for.
   */
  static String instruction(String languageName, String nativeInstruction) {
    String base =
        "Language: think in "
            + languageName
            + ", and answer in it — every response, explanation, summary and clarifying question. The"
            + " language of the user's message does not change this: read it in its own language, but"
            + " always reason and reply in "
            + languageName
            + ". File paths, code, identifiers, and log or command output are never translated.";
    return nativeInstruction == null || nativeInstruction.isBlank()
        ? base
        : base + " " + nativeInstruction;
  }
}
