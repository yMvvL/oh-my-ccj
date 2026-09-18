package com.ccj.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理运行时所用的提示词，以及那个关于提示词本身、而非关于项目的设置：它用哪种语言思考和回答。
 *
 * <p>推理流和平常的模型输出一样，所以对它唯一的杠杆就是提示词要求了什么——而这个杠杆很弱。推理模型从整个
 * 上下文里挑思考所用的语言，并且常常沿用自己开始时的语言，所以这里可靠买到的是：用所选语言写出的<em>答案</em>，
 * 以及在被尊重的模型上用它思考。此处实测的一个中继 deepseek-v4.1，在英文提示词下、在中文提示词下（「你必须
 * 用中文思考」）、以及带一条中文用户消息时，都保持用英文思考，而更新的模型会遵从同一个提示词。所以下面这些
 * 句子问了两遍——一遍英文，与其他规则放在一起；一遍用目标语言，而后者是更强的线索——并且设置里的提示明说：
 * 答案总是跟着走，推理则未必。
 */
public final class Prompts {

  /**
   * 刻意保持简短。模型需要知道的、无法用工具 schema 表达的行为要求都在这里；再长的东西只会每个回合都白烧
   * 上下文。
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

  /** "auto" 表示提示词什么都不说，模型用它喜欢的任何语言回答。 */
  public static final String AUTO = "auto";

  /**
   * 提示词可以要求的一种语言：设置表单存的值、要显示的名字、该语言自己的名字，以及一句<em>用该语言</em>写的
   * 话。
   *
   * <p>用名字而不是代码，是刻意的：模型对「Simplified Chinese」的遵从度远高于「zh-CN」。那句母语句子之所
   * 以在，是因为模型选哪种语言思考，有一部分取决于摆在它面前的文本——一句英文在要求中文，那是弱信号；中文
   * 文本在要求中文，那是强信号——而思考流正是这一点最先显现的地方。
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
   * 设置表单存的那个值，也就是英文名——表单是给人读的，而记录里的顺序就是它展示的顺序。
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

  /** 提示词可以要求的每一种语言，以 {@code value} 字符串形式给出。 */
  public static List<String> languageValues() {
    return List.copyOf(LANGUAGES.keySet());
  }

  /** 所提供的语言，以 {@code {value, label}} 对的形式给出，供设置表单使用。 */
  public static List<String[]> languageChoices() {
    List<String[]> choices = new ArrayList<>();
    LANGUAGES.forEach((value, language) -> choices.add(new String[] {value, language.label()}));
    return choices;
  }

  /**
   * 为配置好的基础提示词和语言拼出提示词。
   *
   * @param system 生效的提示词；null 或空白表示 {@link #DEFAULT_SYSTEM}
   * @param language {@code auto}（或 null/空白）表示对语言不作要求，否则取 {@link #languageValues()}
   *     之一
   */
  public static String system(String system, String language) {
    return system(system, language, null);
  }

  /**
   * 为配置好的基础提示词、语言，以及代理工作所在的项目拼出提示词。
   *
   * <p>各部分按刻意的顺序摆放：先是项目自己的规则，然后是内置规则，最后是语言。项目文件排在<em>最前</em>，
   * 因为它是关于这项工作的具体陈述——构建命令、不许碰的模块——而先读到通用指令的读者，得一边记着它们一边被告
   * 知真正该做的事。内置规则留在每一份提示词里，而不是被文件替换：它们不是项目偏好，而是这个代理的工作方式
   * （「先看再改」、「没有命令证明过就不要说它能用」），而一个加了自己规则的项目并没有要求不再被告知这些。
   * 语言放在最后，因为它关乎回复的形状而不是工作本身，而它是最需要在最后被读到之后仍然生效的那条指令。
   *
   * @param workingDirectory 代理将要运行的目录，其 {@link ProjectPrompt} 规则会被前置；null 表示没有
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
      // 这个构建没有列出的语言：用英文、按给出的名字要求它。表单只是方便，不是对一个人可以用什么语言思考的
      // 限制。
      return withProject + "\n\n" + instruction(asked, null);
    }
    return withProject + "\n\n" + instruction(known.nativeName(), known.nativeInstruction());
  }

  /**
   * 那句话本身，单独成方法，好让测试能钉住模型被告知的内容措辞，而不必在这里重复一遍。
   *
   * <p>刻意分成两部分。第一部分用英文陈述规则，那里是这个代理其他规则所在之处，也是模型会当作指令来读的
   * 东西。第二部分用目标语言说同一件事，因为那才是真正决定模型接下来写出的文本用什么语言的东西——包括它的
   * 思考，也就是这个方法存在的理由。
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
