package fr.cafat.meta.parse.kotlin;

import fr.cafat.meta.extract.Diagnostics;
import fr.cafat.meta.model.Source;
import fr.cafat.meta.parse.SpoonLoader;
import fr.cafat.meta.spoon.Provenance;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.*;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.CtScanner;

/**
 * Traduit des sources Kotlin (arbre PSI, sans résolution sémantique) en éléments du modèle Spoon,
 * pour que les analyses Java s'appliquent telles quelles. La traduction est syntaxique et
 * déterministe : ce qui n'est pas compris devient un snippet, jamais une exception.
 *
 * <p>Trois passes : déclaration des types (pour la résolution croisée entre fichiers), squelettes
 * des membres, puis corps et annotations (tâches différées, exécutées dans l'ordre du source).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class KotlinToSpoon {

  static final String UNSUPPORTED = "KOTLIN_UNSUPPORTED";
  static final String PARSE_ERROR = "PARSE_ERROR";
  public static final String META_OFFSET = "meta.offset";
  public static final String META_NULLABLE = "meta.nullable";
  public static final String META_KIND = "meta.kotlin.kind";
  public static final String META_ARG_NAME = "meta.argName";

  /** Types Kotlin usuels et leur équivalent JVM. */
  private static final Map<String, String> DEFAULT_TYPES = table(
      "String", "java.lang.String", "CharSequence", "java.lang.CharSequence",
      "Long", "java.lang.Long", "Int", "java.lang.Integer", "Boolean", "java.lang.Boolean",
      "Double", "java.lang.Double", "Float", "java.lang.Float", "Short", "java.lang.Short",
      "Byte", "java.lang.Byte", "Char", "java.lang.Character", "Number", "java.lang.Number",
      "Any", "java.lang.Object", "Nothing", "java.lang.Void", "Unit", "kotlin.Unit",
      "Comparable", "java.lang.Comparable", "StringBuilder", "java.lang.StringBuilder",
      "Throwable", "java.lang.Throwable", "Exception", "java.lang.Exception",
      "Error", "java.lang.Error", "RuntimeException", "java.lang.RuntimeException",
      "IllegalArgumentException", "java.lang.IllegalArgumentException",
      "IllegalStateException", "java.lang.IllegalStateException",
      "UnsupportedOperationException", "java.lang.UnsupportedOperationException",
      "NullPointerException", "java.lang.NullPointerException",
      "IndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException",
      "NumberFormatException", "java.lang.NumberFormatException",
      "ClassCastException", "java.lang.ClassCastException",
      "ArithmeticException", "java.lang.ArithmeticException",
      "NoSuchElementException", "java.util.NoSuchElementException",
      "List", "java.util.List", "MutableList", "java.util.List",
      "Set", "java.util.Set", "MutableSet", "java.util.Set",
      "Map", "java.util.Map", "MutableMap", "java.util.Map",
      "Collection", "java.util.Collection", "MutableCollection", "java.util.Collection",
      "Iterable", "java.lang.Iterable", "MutableIterable", "java.lang.Iterable",
      "Iterator", "java.util.Iterator", "MutableIterator", "java.util.Iterator",
      "ArrayList", "java.util.ArrayList", "HashMap", "java.util.HashMap",
      "LinkedHashMap", "java.util.LinkedHashMap", "HashSet", "java.util.HashSet",
      "LinkedHashSet", "java.util.LinkedHashSet",
      "Pair", "kotlin.Pair", "Triple", "kotlin.Triple", "Regex", "kotlin.text.Regex",
      "Sequence", "kotlin.sequences.Sequence");

  private static final Map<String, Class<?>> PRIMITIVE_ARRAYS = Map.of(
      "IntArray", int.class, "LongArray", long.class, "ShortArray", short.class,
      "ByteArray", byte.class, "CharArray", char.class, "BooleanArray", boolean.class,
      "DoubleArray", double.class, "FloatArray", float.class);

  private static final Set<String> ARRAY_FACTORIES = Set.of("arrayOf", "emptyArray",
      "arrayOfNulls", "intArrayOf", "longArrayOf", "shortArrayOf", "byteArrayOf", "charArrayOf",
      "booleanArrayOf", "doubleArrayOf", "floatArrayOf");

  /** Fonctions dont le résultat est une chaîne. */
  private static final Set<String> STRING_FUNCTIONS = Set.of("trimIndent", "trimMargin", "trim",
      "trimStart", "trimEnd", "format", "toString", "substring", "substringBefore",
      "substringAfter", "substringBeforeLast", "substringAfterLast", "uppercase", "lowercase",
      "toUpperCase", "toLowerCase", "replace", "replaceFirst", "joinToString", "padStart",
      "padEnd", "removePrefix", "removeSuffix", "removeSurrounding", "repeat", "capitalize",
      "decapitalize", "buildString", "ifBlank", "ifEmpty", "orEmpty", "prependIndent",
      "replaceIndent");

  private static final Map<String, String> RETURN_TYPES = table(
      "listOf", "java.util.List", "mutableListOf", "java.util.List", "emptyList",
      "java.util.List", "listOfNotNull", "java.util.List", "arrayListOf", "java.util.List",
      "toList", "java.util.List", "toMutableList", "java.util.List", "map", "java.util.List",
      "mapNotNull", "java.util.List", "filter", "java.util.List", "filterNotNull",
      "java.util.List", "flatMap", "java.util.List", "sorted", "java.util.List", "sortedBy",
      "java.util.List", "distinct", "java.util.List",
      "setOf", "java.util.Set", "mutableSetOf", "java.util.Set", "emptySet", "java.util.Set",
      "hashSetOf", "java.util.Set", "toSet", "java.util.Set", "toMutableSet", "java.util.Set",
      "mapOf", "java.util.Map", "mutableMapOf", "java.util.Map", "emptyMap", "java.util.Map",
      "hashMapOf", "java.util.Map", "linkedMapOf", "java.util.Map", "toMap", "java.util.Map",
      "associate", "java.util.Map", "associateBy", "java.util.Map", "groupBy", "java.util.Map",
      "toInt", "java.lang.Integer", "toIntOrNull", "java.lang.Integer", "toLong",
      "java.lang.Long", "toLongOrNull", "java.lang.Long", "toDouble", "java.lang.Double",
      "toBigDecimal", "java.math.BigDecimal", "toBoolean", "java.lang.Boolean",
      "isEmpty", "java.lang.Boolean", "isNotEmpty", "java.lang.Boolean", "isBlank",
      "java.lang.Boolean", "isNotBlank", "java.lang.Boolean", "isNullOrBlank",
      "java.lang.Boolean", "isNullOrEmpty", "java.lang.Boolean", "contains",
      "java.lang.Boolean", "startsWith", "java.lang.Boolean", "endsWith", "java.lang.Boolean",
      "equals", "java.lang.Boolean", "any", "java.lang.Boolean", "all", "java.lang.Boolean",
      "none", "java.lang.Boolean", "count", "java.lang.Integer");

  /** Sélecteurs de littéral de classe : {@code Foo::class.java}. */
  private static final Set<String> CLASS_SELECTORS = Set.of("java", "javaObjectType",
      "javaPrimitiveType", "kotlin");

  private final Factory factory;
  private final Provenance provenance;
  private final Diagnostics diagnostics;
  /** Classes de fichier ({@code FooKt}) par paquet, pour les fonctions et propriétés de premier niveau. */
  private final Map<String, List<CtClass<?>>> fileClasses = new TreeMap<>();
  private final Map<CtType<?>, CtConstructor<?>> primaryCtors = new IdentityHashMap<>();

  public KotlinToSpoon(Factory factory, Path repoRoot, Diagnostics diagnostics) {
    this.factory = factory;
    this.provenance = new Provenance(repoRoot);
    this.diagnostics = diagnostics;
  }

  /** Fichier Kotlin en cours de traduction. */
  private static final class FileUnit {
    final Path path;
    final String rel;
    String text;
    int[] lineStarts;
    KtFile file;
    String pkg = "";
    final Map<String, String> imports = new HashMap<>();
    final List<String> starImports = new ArrayList<>();
    CtClass<?> fileClass;
    final Map<KtClassOrObject, CtType<?>> types = new IdentityHashMap<>();
    final List<CtType<?>> created = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    boolean failed;

    FileUnit(Path path, String rel) {
      this.path = path;
      this.rel = rel;
    }

    /** Ligne (base 1) d'un offset du texte normalisé. */
    int line(int offset) {
      int lo = 0;
      int hi = lineStarts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return lo + 1;
    }
  }

  /** Tâche différée (corps, annotation, initialiseur), rattachée à une position du source. */
  private record Job(int offset, Runnable action) {
  }

  /** Contexte de traduction : type courant, portées des variables et instructions produites. */
  private final class Ctx {
    final FileUnit u;
    final CtType<?> type;
    final boolean staticCtx;
    final Set<String> typeParams = new HashSet<>();
    List<Map<String, CtVariable<?>>> scopes = new ArrayList<>();
    List<CtStatement> sink = new ArrayList<>();
    boolean annotationMode;

    Ctx(FileUnit u, CtType<?> type, boolean staticCtx) {
      this.u = u;
      this.type = type;
      this.staticCtx = staticCtx;
      scopes.add(new HashMap<>());
    }

    /** Copie avec une nouvelle portée au sommet et un nouveau puits d'instructions. */
    Ctx copy() {
      Ctx c = new Ctx(u, type, staticCtx);
      c.typeParams.addAll(typeParams);
      c.scopes = new ArrayList<>(scopes);
      c.scopes.add(new HashMap<>());
      c.annotationMode = annotationMode;
      return c;
    }

    void declare(CtVariable<?> v) {
      if (v != null && v.getSimpleName() != null) {
        scopes.get(scopes.size() - 1).put(v.getSimpleName(), v);
      }
    }

    CtVariable<?> lookup(String name) {
      for (int i = scopes.size() - 1; i >= 0; i--) {
        CtVariable<?> v = scopes.get(i).get(name);
        if (v != null) {
          return v;
        }
      }
      return null;
    }
  }

  private record Branch(List<CtStatement> prefix, CtExpression<?> value) {
  }

  // ---------------------------------------------------------------------------------------------
  // Orchestration
  // ---------------------------------------------------------------------------------------------

  /** Traduit les fichiers donnés ; les types créés sont ajoutés au modèle de la fabrique. */
  public void translate(List<Path> ktFiles) {
    Map<String, FileUnit> units = new TreeMap<>();
    for (Path p : ktFiles) {
      String rel = provenance.relative(p);
      units.putIfAbsent(rel, new FileUnit(p, rel));
    }
    List<FileUnit> loaded = new ArrayList<>();
    for (FileUnit u : units.values()) {
      if (load(u)) {
        loaded.add(u);
      }
    }
    for (FileUnit u : loaded) {
      try {
        declareTypes(u);
      } catch (RuntimeException | StackOverflowError e) {
        u.failed = true;
        diagnostics.error(PARSE_ERROR, "Traduction Kotlin impossible : " + describe(e),
            Source.file(u.rel, null));
      }
    }
    for (FileUnit u : loaded) {
      if (u.failed) {
        continue;
      }
      try {
        declareMembers(u);
      } catch (RuntimeException | StackOverflowError e) {
        diagnostics.error(PARSE_ERROR, "Traduction Kotlin partielle : " + describe(e),
            Source.file(u.rel, null));
      }
    }
    for (FileUnit u : loaded) {
      if (u.failed) {
        continue;
      }
      for (int i = 0; i < u.jobs.size(); i++) {
        Job j = u.jobs.get(i);
        try {
          j.action().run();
        } catch (RuntimeException | StackOverflowError e) {
          diagnostics.info(UNSUPPORTED, "Élément Kotlin non traduit : " + describe(e),
              Source.file(u.rel, u.line(j.offset())));
        }
      }
    }
    for (FileUnit u : loaded) {
      for (CtType<?> t : u.created) {
        inheritMeta(t);
      }
    }
  }

  private boolean load(FileUnit u) {
    try {
      u.text = KotlinPsi.normalize(SpoonLoader.decode(Files.readAllBytes(u.path)));
      u.lineStarts = lineStarts(u.text);
      u.file = KotlinPsi.get().parse(u.path.getFileName().toString(), u.text);
    } catch (IOException | RuntimeException e) {
      u.failed = true;
      diagnostics.error(PARSE_ERROR, "Lecture ou analyse Kotlin impossible : " + describe(e),
          Source.file(u.rel, null));
      return false;
    }
    List<PsiErrorElement> errors = new ArrayList<>();
    for (PsiErrorElement err : PsiTreeUtil.collectElementsOfType(u.file, PsiErrorElement.class)) {
      if (!ignorable(err)) {
        errors.add(err);
      }
    }
    if (!errors.isEmpty()) {
      PsiErrorElement first = errors.get(0);
      diagnostics.error(PARSE_ERROR, errors.size() + " erreur(s) de syntaxe Kotlin, la première : "
          + first.getErrorDescription(), Source.file(u.rel, u.line(first.getTextOffset())));
    }
    return true;
  }

  /** Erreur vide produite par une annotation imbriquée avec {@code @} : syntaxe valide. */
  private static boolean ignorable(PsiErrorElement err) {
    if (err.getTextLength() != 0) {
      return false;
    }
    if (PsiTreeUtil.getParentOfType(err, KtAnnotatedExpression.class) != null) {
      return true;
    }
    for (PsiElement e = err; e != null && !(e instanceof KtFile); e = e.getParent()) {
      PsiElement prev = e.getPrevSibling();
      while (prev instanceof PsiWhiteSpace || prev instanceof PsiErrorElement) {
        prev = prev.getPrevSibling();
      }
      if (prev instanceof KtAnnotatedExpression) {
        return true;
      }
      if (prev != null) {
        return false;
      }
    }
    return false;
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    int[] out = new int[starts.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = starts.get(i);
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Passe 1 : types
  // ---------------------------------------------------------------------------------------------

  private void declareTypes(FileUnit u) {
    KtPackageDirective pd = u.file.getPackageDirective();
    u.pkg = pd == null || pd.isRoot() ? "" : pd.getQualifiedName();
    KtImportList il = u.file.getImportList();
    if (il != null) {
      for (KtImportDirective d : il.getImports()) {
        FqName fq = d.getImportedFqName();
        if (fq == null) {
          continue;
        }
        if (d.isAllUnder()) {
          u.starImports.add(fq.asString());
          continue;
        }
        String alias = d.getAliasName();
        u.imports.putIfAbsent(alias != null ? alias : fq.shortName().asString(), fq.asString());
      }
    }
    if (u.file.isScript()) {
      info(u, u.file, null, "Script Kotlin non traduit");
    }
    CtPackage pkg = u.pkg.isEmpty() ? factory.Package().getRootPackage()
        : factory.Package().getOrCreate(u.pkg);
    KtDeclaration firstTopLevel = null;
    for (KtDeclaration d : u.file.getDeclarations()) {
      if (d instanceof KtClassOrObject c) {
        declareType(u, c, pkg, null);
      } else if (d instanceof KtNamedFunction || d instanceof KtProperty) {
        if (firstTopLevel == null) {
          firstTopLevel = d;
        }
      } else if (!(d instanceof KtScript)) {
        info(u, d, null, "Déclaration Kotlin de premier niveau non traduite : " + kindOf(d));
      }
    }
    if (firstTopLevel != null) {
      createFileClass(u, pkg, firstTopLevel);
    }
  }

  private void declareType(FileUnit u, KtClassOrObject c, CtPackage pkg, CtType<?> owner) {
    if (c instanceof KtEnumEntry) {
      return;
    }
    if (c instanceof KtObjectDeclaration o && o.isCompanion()) {
      if (owner == null) {
        return;
      }
      // Le compagnon est fusionné dans le type englobant (membres statiques)
      u.types.put(c, owner);
      for (KtDeclaration d : c.getDeclarations()) {
        if (d instanceof KtClassOrObject n) {
          declareType(u, n, pkg, owner);
        }
      }
      return;
    }
    String name = c.getName();
    String ownerName = owner == null ? null : owner.getQualifiedName();
    if (name == null) {
      info(u, c, ownerName, "Déclaration Kotlin anonyme non traduite");
      return;
    }
    String qn = owner != null ? owner.getQualifiedName() + "$" + name : qualify(u.pkg, name);
    if (modelTypeExact(qn) != null) {
      info(u, c, qn, "Type déjà présent dans le modèle, déclaration Kotlin ignorée : " + qn);
      return;
    }
    CtType<?> t;
    String kind;
    KtClass k = c instanceof KtClass kc ? kc : null;
    if (k != null && k.isAnnotation()) {
      t = factory.Core().createAnnotationType();
      kind = "annotation";
    } else if (k != null && k.isInterface()) {
      t = factory.Core().createInterface();
      kind = "interface";
    } else if (k != null && k.isEnum()) {
      t = factory.Core().createEnum();
      kind = "enum";
    } else {
      t = factory.Core().createClass();
      if (k == null) {
        kind = "object";
      } else if (k.isData()) {
        kind = "data";
      } else if (k.isSealed()) {
        kind = "sealed";
      } else if (k.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
        kind = "abstract";
      } else if (k.hasModifier(KtTokens.OPEN_KEYWORD)) {
        kind = "open";
      } else {
        kind = "class";
      }
    }
    t.setSimpleName(name);
    if (owner != null) {
      owner.addNestedType(t);
      if (!c.hasModifier(KtTokens.INNER_KEYWORD)) {
        t.addModifier(ModifierKind.STATIC);
      }
    } else {
      pkg.addType(t);
      u.created.add(t);
    }
    t.addModifier(visibility(c, ModifierKind.PUBLIC));
    if (("abstract".equals(kind) || "sealed".equals(kind)) && t instanceof CtClass) {
      t.addModifier(ModifierKind.ABSTRACT);
    }
    markDecl(u, c, t);
    t.putMetadata(Provenance.META_LANG, Provenance.LANG_KOTLIN);
    t.putMetadata(META_KIND, kind);
    comments(c, t);
    u.types.put(c, t);
    for (KtDeclaration d : c.getDeclarations()) {
      if (d instanceof KtClassOrObject n) {
        declareType(u, n, pkg, t);
      }
    }
  }

  private void createFileClass(FileUnit u, CtPackage pkg, KtDeclaration first) {
    String name = jvmName(u);
    if (name == null) {
      String fn = u.path.getFileName().toString();
      String base = fn.endsWith(".kt") ? fn.substring(0, fn.length() - 3) : fn;
      StringBuilder sb = new StringBuilder();
      for (char ch : base.toCharArray()) {
        sb.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
      }
      name = capitalize(sb.toString()) + "Kt";
    }
    String qn = qualify(u.pkg, name);
    CtType<?> existing = modelTypeExact(qn);
    if (existing != null) {
      if (existing instanceof CtClass<?> ec && "file".equals(ec.getMetadata(META_KIND))) {
        u.fileClass = ec;
      } else {
        info(u, first, qn, "Classe de fichier déjà présente dans le modèle : " + qn);
      }
      return;
    }
    CtClass<?> cls = factory.Core().createClass();
    cls.setSimpleName(name);
    pkg.addType(cls);
    cls.setModifiers(EnumSet.of(ModifierKind.PUBLIC, ModifierKind.FINAL));
    markDecl(u, first, cls);
    cls.putMetadata(Provenance.META_LANG, Provenance.LANG_KOTLIN);
    cls.putMetadata(META_KIND, "file");
    u.fileClass = cls;
    u.created.add(cls);
    fileClasses.computeIfAbsent(u.pkg, x -> new ArrayList<>()).add(cls);
  }

  /** Nom imposé par {@code @file:JvmName("X")}, sinon null. */
  private static String jvmName(FileUnit u) {
    for (KtAnnotationEntry e : u.file.getAnnotationEntries()) {
      if (e.getShortName() == null || !"JvmName".equals(e.getShortName().asString())) {
        continue;
      }
      KtValueArgumentList args = e.getValueArgumentList();
      if (args != null && !args.getArguments().isEmpty()) {
        String s = plainString(args.getArguments().get(0).getArgumentExpression());
        if (s != null && !s.isBlank()) {
          return s;
        }
      }
    }
    return null;
  }

  /** Contenu d'une chaîne sans gabarit, sinon null. */
  private static String plainString(KtExpression e) {
    if (!(e instanceof KtStringTemplateExpression s)) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (KtStringTemplateEntry en : s.getEntries()) {
      if (en instanceof KtLiteralStringTemplateEntry) {
        sb.append(en.getText());
      } else if (en instanceof KtEscapeStringTemplateEntry esc) {
        sb.append(esc.getUnescapedValue());
      } else {
        return null;
      }
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Passe 2 : squelettes des membres
  // ---------------------------------------------------------------------------------------------

  private void declareMembers(FileUnit u) {
    for (KtDeclaration d : u.file.getDeclarations()) {
      if (d instanceof KtClassOrObject c) {
        CtType<?> t = u.types.get(c);
        if (t != null) {
          members(u, c, t);
        }
      } else if ((d instanceof KtNamedFunction || d instanceof KtProperty) && u.fileClass != null) {
        member(u, d, u.fileClass, new Ctx(u, u.fileClass, true), true);
      }
    }
    if (u.fileClass != null) {
      Ctx ctx = new Ctx(u, u.fileClass, true);
      for (KtAnnotationEntry e : u.file.getAnnotationEntries()) {
        annotateLater(u, e, u.fileClass, ctx);
      }
    }
  }

  private void members(FileUnit u, KtClassOrObject c, CtType<?> t) {
    boolean companion = c instanceof KtObjectDeclaration o && o.isCompanion();
    boolean statics = c instanceof KtObjectDeclaration;
    Ctx ctx = new Ctx(u, t, statics);
    for (CtTypeParameter tp : t.getFormalCtTypeParameters()) {
      ctx.typeParams.add(tp.getSimpleName());
    }
    if (!companion) {
      if (c instanceof KtClass k) {
        typeParameters(k.getTypeParameters(), t, ctx);
      }
      for (KtAnnotationEntry e : c.getAnnotationEntries()) {
        annotateLater(u, e, t, ctx);
      }
      supertypes(u, c, t, ctx);
      if (t instanceof CtEnum<?> en) {
        enumEntries(u, c, en, ctx);
      }
      if (t instanceof CtAnnotationType<?> at) {
        annotationMembers(u, (KtClass) c, at, ctx);
      } else if (c instanceof KtClass k && t instanceof CtClass<?> cls) {
        primaryConstructor(u, k, cls, ctx);
      }
    }
    KtClassBody body = c.getBody();
    if (body == null) {
      return;
    }
    for (KtDeclaration d : body.getDeclarations()) {
      if (d instanceof KtEnumEntry) {
        continue;
      }
      if (d instanceof KtObjectDeclaration o && o.isCompanion()) {
        members(u, o, t);
      } else if (d instanceof KtClassOrObject n) {
        CtType<?> nt = u.types.get(n);
        if (nt != null) {
          members(u, n, nt);
        }
      } else {
        member(u, d, t, ctx, statics);
      }
    }
  }

  private void typeParameters(List<KtTypeParameter> params, CtFormalTypeDeclarer owner,
      Ctx ctx) {
    for (KtTypeParameter tp : params) {
      if (tp.getName() != null) {
        ctx.typeParams.add(tp.getName());
      }
    }
    for (KtTypeParameter tp : params) {
      if (tp.getName() == null) {
        continue;
      }
      CtTypeParameter p = factory.Core().createTypeParameter();
      p.setSimpleName(tp.getName());
      if (tp.getExtendsBound() != null) {
        p.setSuperclass(resolveType(tp.getExtendsBound(), ctx));
      }
      owner.addFormalCtTypeParameter(p);
    }
  }

  private void supertypes(FileUnit u, KtClassOrObject c, CtType<?> t, Ctx ctx) {
    for (KtSuperTypeListEntry s : c.getSuperTypeListEntries()) {
      KtTypeReference tr = s.getTypeReference();
      if (tr == null) {
        continue;
      }
      CtTypeReference<?> ref = resolveType(tr, ctx);
      if (!(t instanceof CtClass<?> cls) || t instanceof CtEnum) {
        t.addSuperInterface(ref);
        continue;
      }
      boolean isClass = s instanceof KtSuperTypeCallEntry
          || (declaration(ref) instanceof CtClass<?> dc && !(dc instanceof CtEnum));
      if (isClass && cls.getSuperclass() == null) {
        cls.setSuperclass(ref);
      } else {
        t.addSuperInterface(ref);
      }
    }
  }

  private void enumEntries(FileUnit u, KtClassOrObject c, CtEnum<?> en, Ctx ctx) {
    KtClassBody body = c.getBody();
    if (body == null) {
      return;
    }
    for (KtDeclaration d : body.getDeclarations()) {
      if (!(d instanceof KtEnumEntry e) || e.getName() == null) {
        continue;
      }
      CtEnumValue<Object> v = factory.Core().createEnumValue();
      v.setSimpleName(e.getName());
      v.setModifiers(EnumSet.of(ModifierKind.PUBLIC, ModifierKind.STATIC, ModifierKind.FINAL));
      v.setType((CtTypeReference) en.getReference());
      markDecl(u, e, v);
      comments(e, v);
      ((CtEnum) en).addEnumValue(v);
      for (KtAnnotationEntry a : e.getAnnotationEntries()) {
        annotateLater(u, a, v, ctx);
      }
      KtInitializerList il = e.getInitializerList();
      if (il != null) {
        for (KtSuperTypeListEntry s : il.getInitializers()) {
          if (s instanceof KtSuperTypeCallEntry sc) {
            later(u, e, () -> {
              Ctx x = ctx.copy();
              v.setDefaultExpression((CtExpression) constructorCall(en.getReference(),
                  arguments(sc, x), null, x));
            });
          }
        }
      }
      if (e.getBody() != null && !e.getBody().getDeclarations().isEmpty()) {
        info(u, e, en.getQualifiedName(), "Corps de constante d'énumération non traduit : "
            + e.getName());
      }
    }
  }

  private void annotationMembers(FileUnit u, KtClass k, CtAnnotationType<?> at, Ctx ctx) {
    KtPrimaryConstructor pc = k.getPrimaryConstructor();
    if (pc == null) {
      return;
    }
    for (KtParameter p : pc.getValueParameters()) {
      if (p.getName() == null) {
        continue;
      }
      CtAnnotationMethod<Object> m = factory.Core().createAnnotationMethod();
      m.setSimpleName(p.getName());
      CtTypeReference<?> type = resolveType(p.getTypeReference(), ctx);
      if (p.isVarArg()) {
        type = factory.Type().createArrayReference(type);
      }
      m.setType((CtTypeReference) type);
      m.addModifier(ModifierKind.PUBLIC);
      markDecl(u, p, m);
      KtExpression def = p.getDefaultValue();
      if (def != null) {
        later(u, p, () -> {
          Ctx x = ctx.copy();
          x.annotationMode = true;
          m.setDefaultExpression((CtExpression) expr(def, x));
        });
      }
      ((CtAnnotationType) at).addMethod(m);
    }
  }

  private void primaryConstructor(FileUnit u, KtClass k, CtClass<?> cls, Ctx ctx) {
    KtPrimaryConstructor pc = k.getPrimaryConstructor();
    if (pc == null && !k.getSecondaryConstructors().isEmpty()) {
      return;
    }
    CtConstructor<Object> ctor = factory.Core().createConstructor();
    ctor.addModifier(cls instanceof CtEnum ? ModifierKind.PRIVATE
        : pc == null ? ModifierKind.PUBLIC : visibility(pc, ModifierKind.PUBLIC));
    CtBlock<Object> body = factory.Core().createBlock();
    Ctx cctx = ctx.copy();
    if (pc != null) {
      for (KtParameter p : pc.getValueParameters()) {
        CtParameter<Object> param = parameter(u, p, cctx);
        ctor.addParameter(param);
        cctx.declare(param);
        if (!p.hasValOrVar()) {
          for (KtAnnotationEntry e : p.getAnnotationEntries()) {
            annotateLater(u, e, param, cctx);
          }
          continue;
        }
        CtField<Object> f = factory.Core().createField();
        f.setSimpleName(param.getSimpleName());
        f.setType(cl(param.getType()));
        f.addModifier(ModifierKind.PRIVATE);
        if (!p.isMutable()) {
          f.addModifier(ModifierKind.FINAL);
        }
        nullable(f, f.getType());
        markDecl(u, p, f);
        comments(p, f);
        cls.addField(f);
        // Toutes les annotations de la propriété vont sur le champ, quelle que soit la cible
        for (KtAnnotationEntry e : p.getAnnotationEntries()) {
          annotateLater(u, e, f, cctx);
        }
        if (p.getDefaultValue() != null) {
          initializerLater(u, p.getDefaultValue(), f, cls, false);
        }
        CtFieldWrite<Object> fw = factory.Core().createFieldWrite();
        fw.setVariable(f.getReference());
        fw.setTarget(thisAccess(cls, true));
        CtAssignment<Object, Object> a = factory.Core().createAssignment();
        a.setAssigned(fw);
        a.setAssignment(factory.Code().createVariableRead(param.getReference(), false));
        a.setType(cl(f.getType()));
        markDecl(u, p, a);
        body.addStatement(a);
      }
    }
    for (KtSuperTypeListEntry s : k.getSuperTypeListEntries()) {
      if (s instanceof KtSuperTypeCallEntry sc && sc.getValueArgumentList() != null
          && !sc.getValueArgumentList().getArguments().isEmpty()) {
        later(u, sc, () -> {
          Ctx x = cctx.copy();
          List<CtExpression<?>> args = arguments(sc, x);
          CtTypeReference<?> owner = cls.getSuperclass() != null ? cls.getSuperclass()
              : objectType();
          CtInvocation<?> inv = invocation(null, execRef(owner, false, owner, "<init>", args),
              args);
          mark(u, sc, inv);
          body.insertBegin(inv);
          if (!x.sink.isEmpty()) {
            body.insertBegin(statementList(x.sink));
          }
        });
      }
    }
    ctor.setBody(body);
    if (pc == null) {
      ctor.setImplicit(true);
      markDecl(u, k, ctor);
    } else {
      markDecl(u, pc, ctor);
      for (KtAnnotationEntry e : pc.getAnnotationEntries()) {
        annotateLater(u, e, ctor, cctx);
      }
    }
    ((CtClass) cls).addConstructor(ctor);
    primaryCtors.put(cls, ctor);
  }

  private CtParameter<Object> parameter(FileUnit u, KtParameter p, Ctx ctx) {
    CtParameter<Object> param = factory.Core().createParameter();
    String name = p.getName();
    param.setSimpleName(name != null ? name : "$p" + p.getTextRange().getStartOffset());
    CtTypeReference<?> type = p.getTypeReference() != null ? resolveType(p.getTypeReference(), ctx)
        : objectType();
    if (p.isVarArg()) {
      type = factory.Type().createArrayReference(type);
      param.setVarArgs(true);
    }
    param.setType((CtTypeReference) type);
    nullable(param, type);
    markDecl(u, p, param);
    return param;
  }

  private void member(FileUnit u, KtDeclaration d, CtType<?> t, Ctx ctx, boolean statics) {
    if (d instanceof KtProperty p) {
      property(u, p, t, ctx, statics);
    } else if (d instanceof KtNamedFunction f) {
      function(u, f, t, ctx, statics);
    } else if (d instanceof KtSecondaryConstructor sc) {
      secondaryConstructor(u, sc, t, ctx);
    } else if (d instanceof KtAnonymousInitializer ai) {
      initBlock(u, ai, t, ctx, statics);
    } else {
      info(u, d, t.getQualifiedName(), "Déclaration Kotlin non traduite : " + kindOf(d));
    }
  }

  private void property(FileUnit u, KtProperty p, CtType<?> t, Ctx ctx, boolean statics) {
    String cls = t.getQualifiedName();
    String name = p.getName();
    if (name == null) {
      return;
    }
    if (p.getReceiverTypeReference() != null) {
      info(u, p, cls, "Propriété d'extension Kotlin non traduite : " + name);
      return;
    }
    CtTypeReference<?> type = p.getTypeReference() != null ? resolveType(p.getTypeReference(), ctx)
        : inferType(p.getInitializer(), ctx);
    if (t instanceof CtInterface<?>) {
      CtMethod<Object> m = factory.Core().createMethod();
      m.setSimpleName(getterName(name));
      m.setType((CtTypeReference) cl(type));
      m.addModifier(ModifierKind.PUBLIC);
      nullable(m, type);
      markDecl(u, p, m);
      comments(p, m);
      KtPropertyAccessor g = p.getGetter();
      if (g != null && g.hasBody()) {
        m.setDefaultMethod(true);
        later(u, g, () -> m.setBody(body(g, m, ctx, true)));
      } else {
        m.addModifier(ModifierKind.ABSTRACT);
      }
      for (KtAnnotationEntry e : p.getAnnotationEntries()) {
        annotateLater(u, e, m, ctx);
      }
      t.addMethod(m);
      return;
    }
    CtField<Object> f = factory.Core().createField();
    f.setSimpleName(name);
    f.setType((CtTypeReference) cl(type));
    if (p.hasModifier(KtTokens.CONST_KEYWORD)) {
      f.setModifiers(EnumSet.of(ModifierKind.PUBLIC, ModifierKind.STATIC, ModifierKind.FINAL));
    } else {
      f.addModifier(ModifierKind.PRIVATE);
      if (!p.isVar()) {
        f.addModifier(ModifierKind.FINAL);
      }
      if (statics) {
        f.addModifier(ModifierKind.STATIC);
      }
    }
    nullable(f, type);
    markDecl(u, p, f);
    comments(p, f);
    t.addField(f);
    for (KtAnnotationEntry e : p.getAnnotationEntries()) {
      annotateLater(u, e, f, ctx);
    }
    KtExpression init = p.getInitializer();
    if (init == null && p.getDelegate() != null) {
      init = p.getDelegate().getExpression();
    }
    if (init != null) {
      initializerLater(u, init, f, t, f.isStatic());
    }
    for (KtPropertyAccessor a : p.getAccessors()) {
      if (!a.hasBody()) {
        continue;
      }
      CtMethod<Object> m = factory.Core().createMethod();
      Ctx x = ctx.copy();
      x.declare(f);
      x.scopes.get(x.scopes.size() - 1).put("field", f);
      if (a.isGetter()) {
        m.setSimpleName(getterName(name));
        m.setType((CtTypeReference) cl(type));
      } else {
        m.setSimpleName("set" + capitalize(name));
        m.setType((CtTypeReference) factory.Type().voidPrimitiveType());
        KtParameter ap = a.getParameter();
        CtParameter<Object> param = ap != null ? parameter(u, ap, x)
            : factory.Core().createParameter();
        if (ap == null) {
          param.setSimpleName("value");
        }
        if (ap == null || ap.getTypeReference() == null) {
          param.setType((CtTypeReference) cl(type));
        }
        m.addParameter(param);
      }
      m.addModifier(visibility(a, ModifierKind.PUBLIC));
      if (statics) {
        m.addModifier(ModifierKind.STATIC);
      }
      markDecl(u, a, m);
      for (KtAnnotationEntry e : a.getAnnotationEntries()) {
        annotateLater(u, e, m, x);
      }
      t.addMethod(m);
      boolean getter = a.isGetter();
      later(u, a, () -> m.setBody(body(a, m, x, getter)));
    }
  }

  /** Initialiseur de champ traduit après la déclaration de tous les membres. */
  private void initializerLater(FileUnit u, KtExpression init, CtField<?> f, CtType<?> t,
      boolean isStatic) {
    later(u, init, () -> {
      Ctx x = new Ctx(u, t, isStatic);
      for (CtTypeParameter tp : t.getFormalCtTypeParameters()) {
        x.typeParams.add(tp.getSimpleName());
      }
      if (!isStatic) {
        CtConstructor<?> pc = primaryCtors.get(t);
        if (pc != null) {
          for (CtParameter<?> p : pc.getParameters()) {
            x.declare(p);
          }
        }
      }
      ((CtField) f).setDefaultExpression(expr(init, x));
      if (!x.sink.isEmpty() && t instanceof CtClass<?> c) {
        CtAnonymousExecutable ae = factory.Core().createAnonymousExecutable();
        if (isStatic) {
          ae.addModifier(ModifierKind.STATIC);
        }
        ae.setBody(block(x.sink));
        mark(u, init, ae);
        c.addAnonymousExecutable(ae);
      }
    });
  }

  private void function(FileUnit u, KtNamedFunction fn, CtType<?> t, Ctx ctx, boolean statics) {
    String name = fn.getName();
    if (name == null) {
      return;
    }
    CtMethod<Object> m = factory.Core().createMethod();
    m.setSimpleName(name);
    Ctx x = ctx.copy();
    typeParameters(fn.getTypeParameters(), m, x);
    CtTypeReference<?> ret;
    KtTypeReference tr = fn.getTypeReference();
    boolean unit = tr != null && isUnit(tr);
    if (tr != null && !unit) {
      ret = resolveType(tr, x);
    } else if (tr == null && fn.hasBody() && !fn.hasBlockBody()) {
      ret = inferType(fn.getBodyExpression(), x);
    } else {
      ret = factory.Type().voidPrimitiveType();
    }
    m.setType((CtTypeReference) ret);
    nullable(m, ret);
    m.addModifier(visibility(fn, ModifierKind.PUBLIC));
    if (statics) {
      m.addModifier(ModifierKind.STATIC);
    }
    if (!fn.hasBody()) {
      m.addModifier(ModifierKind.ABSTRACT);
    } else if (t instanceof CtInterface<?>) {
      m.setDefaultMethod(true);
    }
    if (fn.getReceiverTypeReference() != null) {
      CtParameter<Object> recv = factory.Core().createParameter();
      recv.setSimpleName("$receiver");
      recv.setType((CtTypeReference) resolveType(fn.getReceiverTypeReference(), x));
      markDecl(u, fn.getReceiverTypeReference(), recv);
      m.addParameter(recv);
    }
    for (KtParameter p : fn.getValueParameters()) {
      CtParameter<Object> param = parameter(u, p, x);
      m.addParameter(param);
      for (KtAnnotationEntry e : p.getAnnotationEntries()) {
        annotateLater(u, e, param, x);
      }
    }
    markDecl(u, fn, m);
    comments(fn, m);
    for (KtAnnotationEntry e : fn.getAnnotationEntries()) {
      annotateLater(u, e, m, x);
    }
    t.addMethod(m);
    if (fn.hasBody()) {
      boolean returns = !fn.hasBlockBody() && !unit;
      later(u, fn, () -> m.setBody(body(fn, m, x, returns)));
    }
  }

  private void secondaryConstructor(FileUnit u, KtSecondaryConstructor sc, CtType<?> t,
      Ctx ctx) {
    if (!(t instanceof CtClass<?> cls)) {
      info(u, sc, t.getQualifiedName(), "Constructeur Kotlin hors classe non traduit");
      return;
    }
    CtConstructor<Object> c = factory.Core().createConstructor();
    c.addModifier(t instanceof CtEnum ? ModifierKind.PRIVATE
        : visibility(sc, ModifierKind.PUBLIC));
    Ctx x = ctx.copy();
    for (KtParameter p : sc.getValueParameters()) {
      CtParameter<Object> param = parameter(u, p, x);
      c.addParameter(param);
      x.declare(param);
      for (KtAnnotationEntry e : p.getAnnotationEntries()) {
        annotateLater(u, e, param, x);
      }
    }
    markDecl(u, sc, c);
    comments(sc, c);
    for (KtAnnotationEntry e : sc.getAnnotationEntries()) {
      annotateLater(u, e, c, x);
    }
    ((CtClass) cls).addConstructor(c);
    later(u, sc, () -> {
      Ctx bx = x.copy();
      KtConstructorDelegationCall dc = sc.getDelegationCall();
      if (dc != null && !dc.isImplicit()) {
        CtTypeReference<?> owner = dc.isCallToThis() ? cls.getReference()
            : cls.getSuperclass() != null ? cls.getSuperclass() : objectType();
        List<CtExpression<?>> args = arguments(dc, bx);
        CtInvocation<?> inv = invocation(null, execRef(owner, false, owner, "<init>", args), args);
        mark(u, dc, inv);
        bx.sink.add(inv);
      }
      if (sc.getBodyExpression() != null) {
        statements(sc.getBodyExpression(), bx);
      }
      c.setBody(block(bx.sink));
    });
  }

  private void initBlock(FileUnit u, KtAnonymousInitializer ai, CtType<?> t, Ctx ctx,
      boolean statics) {
    if (!(t instanceof CtClass<?> cls)) {
      info(u, ai, t.getQualifiedName(), "Bloc init Kotlin hors classe non traduit");
      return;
    }
    later(u, ai, () -> {
      Ctx x = ctx.copy();
      if (!statics) {
        CtConstructor<?> pc = primaryCtors.get(t);
        if (pc != null) {
          for (CtParameter<?> p : pc.getParameters()) {
            x.declare(p);
          }
        }
      }
      KtExpression b = ai.getBody();
      if (b instanceof KtBlockExpression blk) {
        statements(blk, x);
      } else if (b != null) {
        statement(b, x);
      }
      CtAnonymousExecutable ae = factory.Core().createAnonymousExecutable();
      if (statics) {
        ae.addModifier(ModifierKind.STATIC);
      }
      ae.setBody(block(x.sink));
      mark(u, ai, ae);
      cls.addAnonymousExecutable(ae);
    });
  }

  private void annotateLater(FileUnit u, KtAnnotationEntry e, CtElement target, Ctx ctx) {
    later(u, e, () -> {
      CtAnnotation<?> a = annotation(e, ctx.copy());
      if (a != null) {
        target.addAnnotation(a);
      }
    });
  }

  private void later(FileUnit u, PsiElement psi, Runnable r) {
    u.jobs.add(new Job(psi.getTextRange().getStartOffset(), r));
  }

  // ---------------------------------------------------------------------------------------------
  // Résolution des types
  // ---------------------------------------------------------------------------------------------

  private CtTypeReference<?> resolveType(KtTypeReference tr, Ctx ctx) {
    if (tr == null) {
      return objectType();
    }
    try {
      CtTypeReference<?> r = typeOf(tr.getTypeElement(), ctx);
      return r == null ? objectType() : r;
    } catch (RuntimeException e) {
      return objectType();
    }
  }

  private CtTypeReference<?> typeOf(KtTypeElement te, Ctx ctx) {
    if (te instanceof KtNullableType n) {
      CtTypeReference<?> r = typeOf(n.getInnerType(), ctx);
      if (r != null) {
        r.putMetadata(META_NULLABLE, Boolean.TRUE);
      }
      return r;
    }
    if (te instanceof KtUserType ut) {
      return userType(ut, ctx);
    }
    if (te instanceof KtFunctionType ft) {
      return factory.Type().createReference("kotlin.jvm.functions.Function"
          + ft.getParameters().size());
    }
    return objectType();
  }

  private CtTypeReference<?> userType(KtUserType ut, Ctx ctx) {
    List<String> segs = new ArrayList<>();
    for (KtUserType x = ut; x != null; x = x.getQualifier()) {
      segs.add(0, x.getReferencedName());
    }
    String last = ut.getReferencedName();
    List<KtTypeProjection> projections = ut.getTypeArguments();
    if (segs.size() == 1 && "Array".equals(last) && projections.size() == 1) {
      return factory.Type().createArrayReference(projection(projections.get(0), ctx));
    }
    if (segs.size() == 1 && PRIMITIVE_ARRAYS.containsKey(last)
        && knownType(last, ctx, true) == null) {
      return factory.Type().createArrayReference(
          factory.Type().createReference(PRIMITIVE_ARRAYS.get(last)));
    }
    CtTypeReference<?> base = segs.size() == 1 ? resolveSimpleType(last, ctx)
        : resolveQualifiedType(segs, ctx);
    if (base instanceof CtTypeParameterReference || projections.isEmpty()) {
      return base;
    }
    List<CtTypeReference<?>> args = new ArrayList<>();
    for (KtTypeProjection p : projections) {
      args.add(projection(p, ctx));
    }
    base.setActualTypeArguments(args);
    return base;
  }

  private CtTypeReference<?> projection(KtTypeProjection p, Ctx ctx) {
    KtProjectionKind k = p.getProjectionKind();
    if (k == KtProjectionKind.STAR) {
      return factory.Core().createWildcardReference();
    }
    CtTypeReference<?> r = resolveType(p.getTypeReference(), ctx);
    if (k == KtProjectionKind.OUT || k == KtProjectionKind.IN) {
      CtWildcardReference w = factory.Core().createWildcardReference();
      w.setBoundingType(r);
      w.setUpper(k == KtProjectionKind.OUT);
      return w;
    }
    return r;
  }

  /**
   * Type désigné par un nom simple, ou null : paramètre de type, type englobant ou imbriqué, import,
   * même paquet, type Kotlin usuel, import étoile connu du modèle.
   */
  private CtTypeReference<?> knownType(String name, Ctx ctx, boolean typePosition) {
    if (ctx.typeParams.contains(name)) {
      return factory.Type().createTypeParameterReference(name);
    }
    for (CtType<?> t = ctx.type; t != null; t = t.getDeclaringType()) {
      if ("file".equals(t.getMetadata(META_KIND))) {
        break;
      }
      if (name.equals(t.getSimpleName())) {
        return t.getReference();
      }
      CtType<?> n = t.getNestedType(name);
      if (n != null) {
        return n.getReference();
      }
    }
    String imp = ctx.u.imports.get(name);
    if (imp != null) {
      CtType<?> mt = modelType(imp);
      if (mt != null) {
        return mt.getReference();
      }
      if (typePosition || isUpper(name)) {
        return fqnType(imp);
      }
    }
    CtType<?> same = modelTypeExact(qualify(ctx.u.pkg, name));
    if (same != null) {
      return same.getReference();
    }
    String def = DEFAULT_TYPES.get(name);
    if (def != null) {
      return factory.Type().createReference(def);
    }
    for (String star : ctx.u.starImports) {
      CtType<?> st = modelType(star + "." + name);
      if (st != null) {
        return st.getReference();
      }
    }
    return null;
  }

  private CtTypeReference<?> resolveSimpleType(String name, Ctx ctx) {
    CtTypeReference<?> t = knownType(name, ctx, true);
    return t != null ? t : factory.Type().createReference(qualify(ctx.u.pkg, name));
  }

  private CtTypeReference<?> resolveQualifiedType(List<String> segs, Ctx ctx) {
    CtTypeReference<?> head = isUpper(segs.get(0)) ? knownType(segs.get(0), ctx, true) : null;
    if (head != null && !(head instanceof CtTypeParameterReference)) {
      StringBuilder qn = new StringBuilder(head.getQualifiedName());
      for (int i = 1; i < segs.size(); i++) {
        qn.append('$').append(segs.get(i));
      }
      CtType<?> mt = modelTypeExact(qn.toString());
      return mt != null ? mt.getReference() : factory.Type().createReference(qn.toString());
    }
    return fqnType(String.join(".", segs));
  }

  /** Référence d'un nom pleinement qualifié (types imbriqués en {@code $}, types Kotlin mappés). */
  private CtTypeReference<?> fqnType(String fqn) {
    CtType<?> mt = modelType(fqn);
    if (mt != null) {
      return mt.getReference();
    }
    int dot = fqn.lastIndexOf('.');
    String simple = fqn.substring(dot + 1);
    String parent = dot < 0 ? "" : fqn.substring(0, dot);
    if ((parent.equals("kotlin") || parent.startsWith("kotlin.")) && DEFAULT_TYPES.containsKey(
        simple)) {
      return factory.Type().createReference(DEFAULT_TYPES.get(simple));
    }
    return factory.Type().createReference(binaryName(fqn));
  }

  /** {@code a.b.Outer.Inner} → {@code a.b.Outer$Inner} (segments après la première majuscule). */
  private static String binaryName(String fqn) {
    String[] segs = fqn.split("\\.");
    StringBuilder sb = new StringBuilder();
    boolean inType = false;
    for (int i = 0; i < segs.length; i++) {
      if (i > 0) {
        sb.append(inType ? '$' : '.');
      }
      sb.append(segs[i]);
      if (isUpper(segs[i])) {
        inType = true;
      }
    }
    return sb.toString();
  }

  private CtType<?> modelType(String qn) {
    CtType<?> t = modelTypeExact(qn);
    if (t == null) {
      String bin = binaryName(qn);
      if (!bin.equals(qn)) {
        t = modelTypeExact(bin);
      }
    }
    return t;
  }

  private CtType<?> modelTypeExact(String qn) {
    try {
      return factory.Type().get(qn);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Déclaration du modèle d'une référence de type, sans réflexion ; null si inconnue. */
  private static CtType<?> declaration(CtTypeReference<?> ref) {
    if (ref == null || ref instanceof CtTypeParameterReference
        || ref instanceof CtArrayTypeReference || ref.isPrimitive()) {
      return null;
    }
    try {
      return ref.getDeclaration();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Clone d'une référence (une référence n'a qu'un parent), nullabilité comprise. */
  private static <T> CtTypeReference<T> cl(CtTypeReference<T> ref) {
    if (ref == null) {
      return null;
    }
    CtTypeReference<T> c = ref.clone();
    Object n = ref.getMetadata(META_NULLABLE);
    if (n != null) {
      c.putMetadata(META_NULLABLE, n);
    }
    return c;
  }

  private static boolean isUnit(KtTypeReference tr) {
    String text = tr.getText().trim();
    return "Unit".equals(text) || "kotlin.Unit".equals(text);
  }

  // ---------------------------------------------------------------------------------------------
  // Recherche de membres
  // ---------------------------------------------------------------------------------------------

  private CtField<?> fieldIn(CtType<?> t, String name) {
    return fieldIn(t, name, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
  }

  private CtField<?> fieldIn(CtType<?> t, String name, Set<CtType<?>> visited, int depth) {
    if (t == null || depth > 10 || !visited.add(t)) {
      return null;
    }
    if (t instanceof CtEnum<?> en) {
      CtEnumValue<?> v = en.getEnumValue(name);
      if (v != null) {
        return v;
      }
    }
    CtField<?> f = t.getField(name);
    if (f != null) {
      return f;
    }
    f = fieldIn(declaration(t.getSuperclass()), name, visited, depth + 1);
    if (f != null) {
      return f;
    }
    for (CtTypeReference<?> i : t.getSuperInterfaces()) {
      f = fieldIn(declaration(i), name, visited, depth + 1);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  private CtField<?> findField(Ctx ctx, String name) {
    for (CtType<?> t = ctx.type; t != null; t = t.getDeclaringType()) {
      CtField<?> f = fieldIn(t, name);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  private CtMethod<?> findMethod(CtType<?> t, String name, int arity) {
    List<CtMethod<?>> all = new ArrayList<>();
    collectMethods(t, name, all, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    for (CtMethod<?> m : all) {
      int n = m.getParameters().size();
      boolean varargs = n > 0 && m.getParameters().get(n - 1).isVarArgs();
      if (n == arity || (varargs && arity >= n - 1)) {
        return m;
      }
    }
    return all.isEmpty() ? null : all.get(0);
  }

  private void collectMethods(CtType<?> t, String name, List<CtMethod<?>> out,
      Set<CtType<?>> visited, int depth) {
    if (t == null || depth > 10 || !visited.add(t)) {
      return;
    }
    out.addAll(t.getMethodsByName(name));
    collectMethods(declaration(t.getSuperclass()), name, out, visited, depth + 1);
    for (CtTypeReference<?> i : t.getSuperInterfaces()) {
      collectMethods(declaration(i), name, out, visited, depth + 1);
    }
  }

  private CtMethod<?> findMethodInChain(Ctx ctx, String name, int arity) {
    for (CtType<?> t = ctx.type; t != null; t = t.getDeclaringType()) {
      CtMethod<?> m = findMethod(t, name, arity);
      if (m != null) {
        return m;
      }
    }
    return null;
  }

  private CtField<?> topLevelProperty(String pkg, String name) {
    for (CtClass<?> fc : fileClasses.getOrDefault(pkg, List.of())) {
      CtField<?> f = fc.getField(name);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  private CtMethod<?> topLevelFunction(String pkg, String name, int arity) {
    CtMethod<?> any = null;
    for (CtClass<?> fc : fileClasses.getOrDefault(pkg, List.of())) {
      CtMethod<?> m = findMethod(fc, name, arity);
      if (m != null && m.getParameters().size() == arity) {
        return m;
      }
      if (any == null) {
        any = m;
      }
    }
    return any;
  }

  // ---------------------------------------------------------------------------------------------
  // Expressions
  // ---------------------------------------------------------------------------------------------

  /** Traduit une expression ; ce qui n'est pas compris devient un snippet, jamais une exception. */
  private CtExpression<?> expr(KtExpression e, Ctx ctx) {
    if (e == null) {
      return snippet("");
    }
    CtExpression<?> r;
    try {
      r = expr0(e, ctx);
    } catch (RuntimeException | StackOverflowError ex) {
      r = null;
    }
    if (r == null) {
      r = snippet(e.getText());
    }
    if (r.getMetadata(Provenance.META_FILE) == null) {
      mark(ctx.u, e, r);
    }
    return r;
  }

  private CtExpression<?> expr0(KtExpression e, Ctx ctx) {
    if (e instanceof KtParenthesizedExpression p) {
      return expr(p.getExpression(), ctx);
    }
    if (e instanceof KtLabeledExpression l) {
      return expr(l.getBaseExpression(), ctx);
    }
    if (e instanceof KtAnnotatedExpression a) {
      if (ctx.annotationMode && !a.getAnnotationEntries().isEmpty()) {
        return annotation(a.getAnnotationEntries().get(0), ctx);
      }
      return expr(a.getBaseExpression(), ctx);
    }
    if (e instanceof KtBinaryExpressionWithTypeRHS b) {
      return expr(b.getLeft(), ctx);
    }
    if (e instanceof KtStringTemplateExpression s) {
      return template(s, ctx);
    }
    if (e instanceof KtConstantExpression c) {
      return constant(c);
    }
    if (e instanceof KtClassLiteralExpression c) {
      return classLiteral(c, ctx);
    }
    if (e instanceof KtCallableReferenceExpression) {
      return null;
    }
    if (e instanceof KtQualifiedExpression q) {
      return qualified(q, ctx);
    }
    if (e instanceof KtCallExpression c) {
      return call(null, c, ctx);
    }
    if (e instanceof KtSimpleNameExpression n) {
      return name(n, ctx);
    }
    if (e instanceof KtThisExpression) {
      return ctx.staticCtx ? typeAccess(ctx.type.getReference(), false)
          : thisAccess(ctx.type, false);
    }
    if (e instanceof KtSuperExpression) {
      CtSuperAccess<Object> s = factory.Core().createSuperAccess();
      CtTypeReference<?> sup = ctx.type instanceof CtClass<?> c && c.getSuperclass() != null
          ? cl(c.getSuperclass()) : objectType();
      s.setType((CtTypeReference) sup);
      return s;
    }
    if (e instanceof KtLambdaExpression l) {
      return lambda(l, ctx);
    }
    if (e instanceof KtCollectionLiteralExpression c) {
      List<CtExpression<?>> els = new ArrayList<>();
      for (KtExpression x : c.getInnerExpressions()) {
        els.add(expr(x, ctx));
      }
      return newArray(els);
    }
    if (e instanceof KtBinaryExpression b) {
      return binary(b, ctx);
    }
    if (e instanceof KtIsExpression i) {
      CtExpression<?> r = binOp(BinaryOperatorKind.INSTANCEOF, expr(i.getLeftHandSide(), ctx),
          typeAccess(resolveType(i.getTypeReference(), ctx), false), booleanType());
      return i.isNegated() ? unaryOp(UnaryOperatorKind.NOT, r) : r;
    }
    if (e instanceof KtUnaryExpression un) {
      return unary(un, ctx);
    }
    if (e instanceof KtArrayAccessExpression a) {
      CtExpression<?> recv = expr(a.getArrayExpression(), ctx);
      List<CtExpression<?>> idx = new ArrayList<>();
      for (KtExpression x : a.getIndexExpressions()) {
        idx.add(expr(x, ctx));
      }
      return invocation(recv, execRef(typeOrObject(recv), false, objectType(), "get", idx), idx);
    }
    if (e instanceof KtIfExpression i) {
      Branch th = branch(i.getThen(), ctx);
      Branch el = i.getElse() == null ? null : branch(i.getElse(), ctx);
      List<CtExpression<?>> conds = new ArrayList<>();
      conds.add(expr(i.getCondition(), ctx));
      List<String> texts = new ArrayList<>();
      texts.add(i.getCondition() == null ? "true" : i.getCondition().getText());
      List<Branch> branches = new ArrayList<>();
      branches.add(th);
      return choose(conds, texts, branches, el, ctx);
    }
    if (e instanceof KtWhenExpression w) {
      return whenExpr(w, ctx);
    }
    if (e instanceof KtTryExpression t) {
      ctx.sink.add(tryStatement(t, ctx));
      return null;
    }
    if (e instanceof KtBlockExpression b) {
      List<KtExpression> st = b.getStatements();
      for (int i = 0; i < st.size() - 1; i++) {
        statement(st.get(i), ctx);
      }
      if (st.isEmpty()) {
        return null;
      }
      KtExpression last = st.get(st.size() - 1);
      if (isValue(last)) {
        return expr(last, ctx);
      }
      statement(last, ctx);
      return null;
    }
    if (e instanceof KtReturnExpression || e instanceof KtThrowExpression
        || e instanceof KtBreakExpression || e instanceof KtContinueExpression) {
      statement(e, ctx);
      return null;
    }
    return null;
  }

  private CtExpression<?> constant(KtConstantExpression c) {
    String text = c.getText().trim();
    if ("true".equals(text) || "false".equals(text)) {
      return factory.Code().createLiteral(Boolean.parseBoolean(text));
    }
    if ("null".equals(text)) {
      CtLiteral<Object> l = factory.Core().createLiteral();
      l.setValue(null);
      l.setType((CtTypeReference) factory.Type().nullType());
      return l;
    }
    if (text.startsWith("'")) {
      String s = unescape(text.substring(1, Math.max(1, text.length() - 1)));
      return s.length() == 1 ? factory.Code().createLiteral(s.charAt(0)) : null;
    }
    String t = text.replace("_", "").toLowerCase();
    try {
      int radix = 10;
      if (t.startsWith("0x")) {
        radix = 16;
        t = t.substring(2);
      } else if (t.startsWith("0b")) {
        radix = 2;
        t = t.substring(2);
      } else if (t.endsWith("f")) {
        return factory.Code().createLiteral(Float.parseFloat(t.substring(0, t.length() - 1)));
      } else if (t.contains(".") || t.contains("e")) {
        return factory.Code().createLiteral(Double.parseDouble(t));
      }
      boolean isLong = t.endsWith("l");
      t = t.replaceAll("[ul]+$", "");
      BigInteger v = new BigInteger(t, radix);
      if (!isLong && v.bitLength() < 32) {
        return factory.Code().createLiteral(v.intValue());
      }
      return factory.Code().createLiteral(v.longValue());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /** Décodage des échappements Kotlin d'un littéral caractère. */
  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch != '\\' || i + 1 >= s.length()) {
        sb.append(ch);
        continue;
      }
      char n = s.charAt(++i);
      switch (n) {
        case 'n' -> sb.append('\n');
        case 't' -> sb.append('\t');
        case 'r' -> sb.append('\r');
        case 'b' -> sb.append('\b');
        case 'u' -> {
          if (i + 4 < s.length()) {
            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
          }
        }
        default -> sb.append(n);
      }
    }
    return sb.toString();
  }

  /** Chaîne (simple, brute ou gabarit) : littéral, ou concaténation associative à gauche. */
  private CtExpression<?> template(KtStringTemplateExpression s, Ctx ctx) {
    List<CtExpression<?>> parts = new ArrayList<>();
    StringBuilder buf = new StringBuilder();
    boolean pending = false;
    for (KtStringTemplateEntry en : s.getEntries()) {
      if (en instanceof KtEscapeStringTemplateEntry esc) {
        buf.append(esc.getUnescapedValue());
        pending = true;
      } else if (en instanceof KtStringTemplateEntryWithExpression we) {
        CtExpression<?> x = expr(we.getExpression(), ctx);
        if (x instanceof CtLiteral<?> lit
            && (lit.getValue() instanceof String || lit.getValue() instanceof Character)) {
          buf.append(lit.getValue());
          pending = true;
          continue;
        }
        if (pending) {
          parts.add(stringLiteral(buf.toString(), ctx.u, en));
          buf.setLength(0);
          pending = false;
        }
        parts.add(x);
      } else {
        buf.append(en.getText());
        pending = true;
      }
    }
    if (pending) {
      parts.add(stringLiteral(buf.toString(), ctx.u, s));
    }
    if (parts.isEmpty()) {
      return factory.Code().createLiteral("");
    }
    CtExpression<?> acc = parts.get(0);
    for (int i = 1; i < parts.size(); i++) {
      acc = binOp(BinaryOperatorKind.PLUS, acc, parts.get(i), factory.Type().stringType());
      mark(ctx.u, s, acc);
    }
    return acc;
  }

  private CtLiteral<String> stringLiteral(String value, FileUnit u, PsiElement psi) {
    CtLiteral<String> l = factory.Code().createLiteral(value);
    mark(u, psi, l);
    return l;
  }

  /** Nom simple : variable locale, champ, propriété de premier niveau, import ou type. */
  private CtExpression<?> name(KtSimpleNameExpression n, Ctx ctx) {
    String id = n.getReferencedName();
    CtVariable<?> v = ctx.lookup(id);
    if (v instanceof CtField<?> f) {
      return implicitFieldRead(f);
    }
    if (v != null) {
      return factory.Code().createVariableRead((CtVariableReference) v.getReference(), false);
    }
    CtField<?> f = findField(ctx, id);
    if (f == null) {
      f = topLevelProperty(ctx.u.pkg, id);
    }
    if (f != null) {
      return implicitFieldRead(f);
    }
    String imp = ctx.u.imports.get(id);
    if (imp != null) {
      int dot = imp.lastIndexOf('.');
      String parent = dot < 0 ? "" : imp.substring(0, dot);
      String simple = imp.substring(dot + 1);
      if (!parent.isEmpty() && isUpper(lastSegment(parent))) {
        return staticMember(fqnType(parent), simple, true);
      }
      CtField<?> tp = topLevelProperty(parent, simple);
      if (tp != null) {
        return implicitFieldRead(tp);
      }
      if ((isUpper(simple) && !isAllCaps(simple)) || modelType(imp) != null) {
        return typeAccess(fqnType(imp), false);
      }
      CtTypeReference<?> owner = factory.Type().createReference(qualify(parent,
          capitalize(simple) + "Kt"));
      return staticMember(owner, simple, true);
    }
    if (isUpper(id)) {
      CtTypeReference<?> t = knownType(id, ctx, false);
      if (t != null) {
        return typeAccess(t, false);
      }
      if (!isAllCaps(id)) {
        return typeAccess(factory.Type().createReference(qualify(ctx.u.pkg, id)), false);
      }
    }
    // Champ inconnu (hérité d'un type hors du modèle, par exemple)
    CtFieldReference<Object> ref = factory.Field().createReference(ctx.type.getReference(),
        objectType(), id);
    ref.setStatic(ctx.staticCtx);
    return fieldRead(ref, implicitThis(ctx));
  }

  private CtFieldRead<Object> fieldRead(CtFieldReference<?> ref, CtExpression<?> target) {
    CtFieldRead<Object> r = factory.Core().createFieldRead();
    r.setVariable((CtFieldReference) ref);
    r.setTarget((CtExpression) target);
    return r;
  }

  /** Lecture d'un champ connu sans qualificatif ({@code this} ou type implicite). */
  private CtExpression<?> implicitFieldRead(CtField<?> f) {
    CtFieldReference<?> ref = f.getReference();
    boolean isStatic = f.isStatic() || f instanceof CtEnumValue;
    ref.setStatic(isStatic);
    CtType<?> decl = f.getDeclaringType();
    CtExpression<?> target = isStatic ? typeAccess(decl.getReference(), true)
        : thisAccess(decl, true);
    return fieldRead(ref, target);
  }

  /** Membre statique {@code Owner.name} : type imbriqué, champ connu ou référence générique. */
  private CtExpression<?> staticMember(CtTypeReference<?> owner, String name, boolean implicit) {
    CtType<?> ot = declaration(owner);
    if (ot != null) {
      CtType<?> nt = ot.getNestedType(name);
      if (nt != null) {
        return typeAccess(nt.getReference(), implicit);
      }
      CtField<?> f = fieldIn(ot, name);
      if (f != null) {
        CtFieldReference<?> ref = f.getReference();
        ref.setStatic(true);
        return fieldRead(ref, typeAccess(owner, implicit));
      }
    }
    if ("Companion".equals(name)) {
      return typeAccess(owner, implicit);
    }
    CtFieldReference<Object> ref = factory.Field().createReference(cl(owner),
        isAllCaps(name) ? (CtTypeReference) cl(owner) : objectType(), name);
    ref.setStatic(true);
    return fieldRead(ref, typeAccess(owner, implicit));
  }

  private CtExpression<?> instanceMember(CtExpression<?> recv, String name) {
    CtTypeReference<?> rt = recv.getType();
    CtField<?> f = fieldIn(declaration(rt), name);
    CtFieldReference<?> ref;
    if (f != null) {
      ref = f.getReference();
    } else {
      CtTypeReference<?> type = "size".equals(name) || "length".equals(name)
          ? factory.Type().integerType() : objectType();
      ref = factory.Field().createReference(rt != null ? cl(rt) : objectType(),
          (CtTypeReference) type, name);
    }
    return fieldRead(ref, recv);
  }

  private CtExpression<?> member(CtExpression<?> recv, String name) {
    if (recv instanceof CtTypeAccess<?> ta) {
      return staticMember(ta.getAccessedType(), name, false);
    }
    return instanceMember(recv, name);
  }

  /** Expression qualifiée {@code a.b}, {@code a.b(...)}, {@code a?.b}. */
  private CtExpression<?> qualified(KtQualifiedExpression q, Ctx ctx) {
    KtExpression rcv = q.getReceiverExpression();
    KtExpression sel = q.getSelectorExpression();
    if (sel == null) {
      return null;
    }
    if (rcv instanceof KtClassLiteralExpression cle && sel instanceof KtSimpleNameExpression sn
        && CLASS_SELECTORS.contains(sn.getReferencedName())) {
      return classLiteral(cle, ctx);
    }
    List<String> chain = nameChain(rcv);
    if (chain != null && isPackageStart(chain.get(0), ctx)) {
      CtExpression<?> r = packageQualified(chain, sel, ctx);
      if (r != null) {
        return r;
      }
    }
    return select(expr(rcv, ctx), sel, ctx);
  }

  /** Receveur commençant par un nom de paquet : {@code fr.x.Foo.BAR}, {@code fr.x.util.f()}. */
  private CtExpression<?> packageQualified(List<String> chain, KtExpression sel, Ctx ctx) {
    int k = -1;
    for (int i = 0; i < chain.size(); i++) {
      if (isUpper(chain.get(i))) {
        k = i;
        break;
      }
    }
    if (k > 0) {
      CtExpression<?> cur = typeAccess(fqnType(String.join(".", chain.subList(0, k + 1))), false);
      for (int i = k + 1; i < chain.size(); i++) {
        String s = chain.get(i);
        if (cur instanceof CtTypeAccess<?> ta && isUpper(s) && !isAllCaps(s)
            && declaration(ta.getAccessedType()) == null) {
          cur = typeAccess(factory.Type().createReference(
              ta.getAccessedType().getQualifiedName() + "$" + s), false);
        } else {
          cur = member(cur, s);
        }
      }
      return select(cur, sel, ctx);
    }
    if (k == 0) {
      return null;
    }
    String joined = String.join(".", chain);
    String selName = sel instanceof KtCallExpression c ? calleeName(c)
        : sel instanceof KtSimpleNameExpression n ? n.getReferencedName() : null;
    if (selName == null) {
      return null;
    }
    if (isUpper(selName)) {
      CtTypeReference<?> tr = fqnType(joined + "." + selName);
      if (sel instanceof KtCallExpression c) {
        return constructorCall(tr, arguments(c, ctx), c, ctx);
      }
      return typeAccess(tr, false);
    }
    if (!fileClasses.containsKey(joined)) {
      return null;
    }
    if (sel instanceof KtCallExpression c) {
      List<CtExpression<?>> args = arguments(c, ctx);
      CtMethod<?> m = topLevelFunction(joined, selName, args.size());
      return m == null ? null : invocation(typeAccess(m.getDeclaringType().getReference(), true),
          m.getReference(), args);
    }
    CtField<?> f = topLevelProperty(joined, selName);
    return f == null ? null : implicitFieldRead(f);
  }

  /** Vrai si un nom en minuscule n'est ni variable, ni champ, ni import : début de paquet. */
  private boolean isPackageStart(String first, Ctx ctx) {
    return Character.isLowerCase(first.charAt(0)) && ctx.lookup(first) == null
        && findField(ctx, first) == null && topLevelProperty(ctx.u.pkg, first) == null
        && !ctx.u.imports.containsKey(first);
  }

  private CtExpression<?> select(CtExpression<?> recv, KtExpression sel, Ctx ctx) {
    if (sel instanceof KtCallExpression c) {
      return call(recv, c, ctx);
    }
    if (sel instanceof KtSimpleNameExpression n) {
      return member(recv, n.getReferencedName());
    }
    return null;
  }

  /** Noms d'une chaîne {@code a.b.c} (noms simples uniquement), sinon null. */
  private static List<String> nameChain(KtExpression e) {
    if (e instanceof KtSimpleNameExpression n) {
      List<String> out = new ArrayList<>();
      out.add(n.getReferencedName());
      return out;
    }
    if (e instanceof KtDotQualifiedExpression q
        && q.getSelectorExpression() instanceof KtSimpleNameExpression n) {
      List<String> out = nameChain(q.getReceiverExpression());
      if (out != null) {
        out.add(n.getReferencedName());
      }
      return out;
    }
    return null;
  }

  private static String calleeName(KtCallExpression c) {
    return c.getCalleeExpression() instanceof KtSimpleNameExpression n ? n.getReferencedName()
        : null;
  }

  /** Appel, sur un receveur traduit ou sans receveur ({@code recv == null}). */
  private CtExpression<?> call(CtExpression<?> recv, KtCallExpression c, Ctx ctx) {
    if (recv == null) {
      return unqualifiedCall(c, ctx);
    }
    String name = calleeName(c);
    if (name == null) {
      return null;
    }
    List<CtExpression<?>> args = arguments(c, ctx);
    if (recv instanceof CtTypeAccess<?> ta) {
      CtTypeReference<?> owner = ta.getAccessedType();
      CtType<?> ot = declaration(owner);
      if (ot != null) {
        CtType<?> nt = ot.getNestedType(name);
        if (nt != null) {
          return constructorCall(nt.getReference(), args, c, ctx);
        }
        CtMethod<?> m = findMethod(ot, name, args.size());
        if (m != null) {
          return invocation(recv, m.getReference(), args);
        }
      } else if (isUpper(name) && !isAllCaps(name)) {
        return constructorCall(factory.Type().createReference(owner.getQualifiedName() + "$"
            + name), args, c, ctx);
      }
      return invocation(recv, execRef(owner, true, inferReturn(name), name, args), args);
    }
    CtTypeReference<?> rt = recv.getType();
    CtMethod<?> m = findMethod(declaration(rt), name, args.size());
    if (m != null) {
      return invocation(recv, m.getReference(), args);
    }
    CtTypeReference<?> ret = inferReturn(name);
    return invocation(recv, execRef(typeOrObject(recv), false, ret, name, args), args);
  }

  private CtExpression<?> unqualifiedCall(KtCallExpression c, Ctx ctx) {
    KtExpression callee = c.getCalleeExpression();
    if (!(callee instanceof KtSimpleNameExpression n)) {
      CtExpression<?> recv = expr(callee, ctx);
      List<CtExpression<?>> args = arguments(c, ctx);
      return invocation(recv, execRef(typeOrObject(recv), false, objectType(), "invoke", args),
          args);
    }
    String name = n.getReferencedName();
    if (ctx.annotationMode) {
      CtTypeReference<?> t = knownType(name, ctx, false);
      if (t != null || isUpper(name)) {
        CtAnnotation<?> a = annotationOf(t != null ? t : resolveSimpleType(name, ctx), c, ctx);
        mark(ctx.u, c, a);
        return a;
      }
    }
    List<CtExpression<?>> args = arguments(c, ctx);
    if (ARRAY_FACTORIES.contains(name)) {
      return newArray(args);
    }
    CtVariable<?> v = ctx.lookup(name);
    if (v != null && !(v instanceof CtField)) {
      CtExpression<?> recv = factory.Code().createVariableRead((CtVariableReference)
          v.getReference(), false);
      return invocation(recv, execRef(typeOrObject(recv), false, objectType(), "invoke", args),
          args);
    }
    if (isUpper(name)) {
      CtTypeReference<?> t = knownType(name, ctx, false);
      if (t != null) {
        return constructorCall(t, args, c, ctx);
      }
    }
    CtMethod<?> m = findMethodInChain(ctx, name, args.size());
    if (m != null) {
      CtExpression<?> target = m.isStatic() ? typeAccess(m.getDeclaringType().getReference(), true)
          : thisAccess(m.getDeclaringType(), true);
      return invocation(target, m.getReference(), args);
    }
    CtMethod<?> tf = topLevelFunction(ctx.u.pkg, name, args.size());
    String imp = ctx.u.imports.get(name);
    if (tf == null && imp != null) {
      int dot = imp.lastIndexOf('.');
      String parent = dot < 0 ? "" : imp.substring(0, dot);
      String simple = imp.substring(dot + 1);
      tf = topLevelFunction(parent, simple, args.size());
      if (tf == null && !parent.isEmpty() && isUpper(lastSegment(parent))) {
        CtTypeReference<?> owner = fqnType(parent);
        return invocation(typeAccess(owner, true), execRef(owner, true, inferReturn(simple),
            simple, args), args);
      }
    }
    if (tf != null) {
      return invocation(typeAccess(tf.getDeclaringType().getReference(), true), tf.getReference(),
          args);
    }
    CtTypeReference<?> owner = ctx.type.getReference();
    return invocation(implicitThis(ctx), execRef(owner, ctx.staticCtx, inferReturn(name), name,
        args), args);
  }

  private CtExpression<?> implicitThis(Ctx ctx) {
    return ctx.staticCtx ? typeAccess(ctx.type.getReference(), true)
        : thisAccess(ctx.type, true);
  }

  private CtTypeReference<?> inferReturn(String name) {
    if (STRING_FUNCTIONS.contains(name)) {
      return factory.Type().stringType();
    }
    String r = RETURN_TYPES.get(name);
    return r != null ? factory.Type().createReference(r) : objectType();
  }

  private CtExecutableReference<Object> execRef(CtTypeReference<?> owner, boolean isStatic,
      CtTypeReference<?> ret, String name, List<CtExpression<?>> args) {
    List<CtTypeReference<?>> ps = new ArrayList<>();
    for (CtExpression<?> a : args) {
      CtTypeReference<?> at = a.getType();
      ps.add(at == null ? objectType() : at.clone());
    }
    return factory.Executable().createReference(owner == null ? objectType() : cl(owner),
        isStatic, (CtTypeReference) (ret == null ? objectType() : cl(ret)), name, ps);
  }

  private CtInvocation<Object> invocation(CtExpression<?> target, CtExecutableReference<?> ref,
      List<CtExpression<?>> args) {
    CtInvocation<Object> i = factory.Core().createInvocation();
    i.setTarget((CtExpression) target);
    i.setExecutable((CtExecutableReference) ref);
    for (CtExpression<?> a : args) {
      i.addArgument((CtExpression) a);
    }
    return i;
  }

  private CtConstructorCall<Object> constructorCall(CtTypeReference<?> type,
      List<CtExpression<?>> args, KtCallExpression psi, Ctx ctx) {
    CtConstructorCall<Object> cc = factory.Core().createConstructorCall();
    cc.setExecutable(execRef(type, false, type, "<init>", args));
    for (CtExpression<?> a : args) {
      cc.addArgument((CtExpression) a);
    }
    if (psi != null && !psi.getTypeArguments().isEmpty()) {
      List<CtTypeReference<?>> targs = new ArrayList<>();
      for (KtTypeProjection p : psi.getTypeArguments()) {
        targs.add(projection(p, ctx));
      }
      cc.setActualTypeArguments(targs);
    }
    return cc;
  }

  /** Arguments dans l'ordre du source, lambdas finales comprises. */
  private List<CtExpression<?>> arguments(KtCallElement c, Ctx ctx) {
    List<CtExpression<?>> out = new ArrayList<>();
    KtValueArgumentList l = c.getValueArgumentList();
    if (l != null) {
      for (KtValueArgument a : l.getArguments()) {
        out.add(argument(a, ctx));
      }
    }
    for (KtLambdaArgument a : c.getLambdaArguments()) {
      out.add(argument(a, ctx));
    }
    return out;
  }

  private CtExpression<?> argument(KtValueArgument a, Ctx ctx) {
    CtExpression<?> r = expr(a.getArgumentExpression(), ctx);
    if (a.isNamed() && a.getArgumentName() != null) {
      r.putMetadata(META_ARG_NAME, a.getArgumentName().getAsName().asString());
    }
    return r;
  }

  private CtAnnotation<?> annotation(KtAnnotationEntry e, Ctx ctx) {
    KtTypeReference tr = e.getTypeReference();
    CtTypeReference<?> t = tr != null ? resolveType(tr, ctx) : objectType();
    CtAnnotation<?> a = annotationOf(t, e, ctx);
    mark(ctx.u, e, a);
    return a;
  }

  private CtAnnotation<?> annotationOf(CtTypeReference<?> type, KtCallElement call, Ctx ctx) {
    CtAnnotation<java.lang.annotation.Annotation> a = factory.Core().createAnnotation();
    a.setAnnotationType((CtTypeReference) type);
    Ctx x = ctx.copy();
    x.annotationMode = true;
    List<CtExpression<?>> positional = new ArrayList<>();
    KtValueArgumentList l = call.getValueArgumentList();
    if (l != null) {
      for (KtValueArgument arg : l.getArguments()) {
        CtExpression<?> v = expr(arg.getArgumentExpression(), x);
        if (arg.isNamed() && arg.getArgumentName() != null) {
          String key = arg.getArgumentName().getAsName().asString();
          if (!a.getValues().containsKey(key)) {
            a.addValue(key, v);
          }
        } else {
          positional.add(v);
        }
      }
    }
    if (!positional.isEmpty() && !a.getValues().containsKey("value")) {
      a.addValue("value", positional.size() == 1 ? positional.get(0) : newArray(positional));
    }
    return a;
  }

  private CtNewArray<Object> newArray(List<CtExpression<?>> elements) {
    CtNewArray<Object> na = factory.Core().createNewArray();
    CtTypeReference<?> el = elements.isEmpty() || elements.get(0).getType() == null
        ? objectType() : elements.get(0).getType().clone();
    na.setType((CtTypeReference) factory.Type().createArrayReference(el));
    for (CtExpression<?> e : elements) {
      na.addElement((CtExpression) e);
    }
    return na;
  }

  private CtExpression<?> classLiteral(KtClassLiteralExpression cle, Ctx ctx) {
    List<String> chain = nameChain(cle.getReceiverExpression());
    if (chain == null) {
      return null;
    }
    CtTypeReference<?> t = chain.size() == 1 ? resolveSimpleType(chain.get(0), ctx)
        : resolveQualifiedType(chain, ctx);
    return factory.Code().createClassAccess(t);
  }

  // ---------------------------------------------------------------------------------------------
  // Opérateurs
  // ---------------------------------------------------------------------------------------------

  private static final Map<IElementType, BinaryOperatorKind> BINARY_KINDS = Map.ofEntries(
      Map.entry(KtTokens.PLUS, BinaryOperatorKind.PLUS),
      Map.entry(KtTokens.MINUS, BinaryOperatorKind.MINUS),
      Map.entry(KtTokens.MUL, BinaryOperatorKind.MUL),
      Map.entry(KtTokens.DIV, BinaryOperatorKind.DIV),
      Map.entry(KtTokens.PERC, BinaryOperatorKind.MOD),
      Map.entry(KtTokens.EQEQ, BinaryOperatorKind.EQ),
      Map.entry(KtTokens.EQEQEQ, BinaryOperatorKind.EQ),
      Map.entry(KtTokens.EXCLEQ, BinaryOperatorKind.NE),
      Map.entry(KtTokens.EXCLEQEQEQ, BinaryOperatorKind.NE),
      Map.entry(KtTokens.LT, BinaryOperatorKind.LT),
      Map.entry(KtTokens.GT, BinaryOperatorKind.GT),
      Map.entry(KtTokens.LTEQ, BinaryOperatorKind.LE),
      Map.entry(KtTokens.GTEQ, BinaryOperatorKind.GE),
      Map.entry(KtTokens.ANDAND, BinaryOperatorKind.AND),
      Map.entry(KtTokens.OROR, BinaryOperatorKind.OR));

  private static final Map<IElementType, BinaryOperatorKind> COMPOUND_KINDS = Map.of(
      KtTokens.PLUSEQ, BinaryOperatorKind.PLUS, KtTokens.MINUSEQ, BinaryOperatorKind.MINUS,
      KtTokens.MULTEQ, BinaryOperatorKind.MUL, KtTokens.DIVEQ, BinaryOperatorKind.DIV,
      KtTokens.PERCEQ, BinaryOperatorKind.MOD);

  private static boolean isAssignment(KtBinaryExpression b) {
    IElementType op = b.getOperationReference().getReferencedNameElementType();
    return op == KtTokens.EQ || COMPOUND_KINDS.containsKey(op);
  }

  private CtExpression<?> binary(KtBinaryExpression b, Ctx ctx) {
    IElementType op = b.getOperationReference().getReferencedNameElementType();
    if (isAssignment(b)) {
      assignment(b, ctx);
      return null;
    }
    if (op == KtTokens.ELVIS) {
      return elvis(b, ctx);
    }
    CtExpression<?> l = expr(b.getLeft(), ctx);
    CtExpression<?> r = expr(b.getRight(), ctx);
    if (op == KtTokens.IN_KEYWORD || op == KtTokens.NOT_IN) {
      List<CtExpression<?>> args = new ArrayList<>(List.of(l));
      CtExpression<?> c = invocation(r, execRef(typeOrObject(r), false, booleanType(), "contains",
          args), args);
      return op == KtTokens.NOT_IN ? unaryOp(UnaryOperatorKind.NOT, c) : c;
    }
    if (op == KtTokens.RANGE || op == KtTokens.RANGE_UNTIL || op == KtTokens.IDENTIFIER) {
      String name = op == KtTokens.RANGE ? "rangeTo" : op == KtTokens.RANGE_UNTIL ? "rangeUntil"
          : b.getOperationReference().getReferencedName();
      List<CtExpression<?>> args = new ArrayList<>(List.of(r));
      return invocation(l, execRef(typeOrObject(l), false, inferReturn(name), name, args), args);
    }
    BinaryOperatorKind kind = BINARY_KINDS.get(op);
    if (kind == null) {
      return null;
    }
    CtTypeReference<?> type;
    switch (kind) {
      case PLUS -> type = isString(l) || isString(r) ? factory.Type().stringType()
          : typeOrObject(l);
      case MINUS, MUL, DIV, MOD -> type = typeOrObject(l);
      default -> type = booleanType();
    }
    return binOp(kind, l, r, type);
  }

  private static boolean isString(CtExpression<?> e) {
    return e.getType() != null && "java.lang.String".equals(e.getType().getQualifiedName());
  }

  /** {@code a ?: b} : conditionnelle ; {@code a ?: return} : garde dans le puits, valeur a. */
  private CtExpression<?> elvis(KtBinaryExpression b, Ctx ctx) {
    CtExpression<?> l = expr(b.getLeft(), ctx);
    KtExpression right = KtPsiUtil.safeDeparenthesize(b.getRight());
    if (right instanceof KtReturnExpression || right instanceof KtThrowExpression
        || right instanceof KtBreakExpression || right instanceof KtContinueExpression) {
      CtIf guard = factory.Core().createIf();
      guard.setCondition((CtExpression) binOp(BinaryOperatorKind.EQ, l.clone(), nullLiteral(),
          booleanType()));
      guard.setThenStatement(blockOf(right, ctx));
      mark(ctx.u, b, guard);
      ctx.sink.add(guard);
      return l;
    }
    CtExpression<?> r = expr(b.getRight(), ctx);
    CtConditional<Object> c = factory.Core().createConditional();
    c.setCondition((CtExpression) binOp(BinaryOperatorKind.NE, l.clone(), nullLiteral(),
        booleanType()));
    c.setThenExpression((CtExpression) l);
    c.setElseExpression((CtExpression) r);
    c.setType((CtTypeReference) cl(typeOrObject(l)));
    return c;
  }

  private CtExpression<?> unary(KtUnaryExpression un, Ctx ctx) {
    IElementType op = un.getOperationReference().getReferencedNameElementType();
    KtExpression base = un.getBaseExpression();
    if (op == KtTokens.EXCLEXCL) {
      return expr(base, ctx);
    }
    if (op == KtTokens.PLUSPLUS || op == KtTokens.MINUSMINUS) {
      CtExpression<?> target = writeAccess(base, ctx);
      if (target == null) {
        return null;
      }
      boolean prefix = un instanceof KtPrefixExpression;
      UnaryOperatorKind k = op == KtTokens.PLUSPLUS
          ? (prefix ? UnaryOperatorKind.PREINC : UnaryOperatorKind.POSTINC)
          : (prefix ? UnaryOperatorKind.PREDEC : UnaryOperatorKind.POSTDEC);
      return unaryOp(k, target);
    }
    UnaryOperatorKind k = op == KtTokens.MINUS ? UnaryOperatorKind.NEG
        : op == KtTokens.PLUS ? UnaryOperatorKind.POS
        : op == KtTokens.EXCL ? UnaryOperatorKind.NOT : null;
    return k == null ? null : unaryOp(k, expr(base, ctx));
  }

  private CtBinaryOperator<Object> binOp(BinaryOperatorKind kind, CtExpression<?> l,
      CtExpression<?> r, CtTypeReference<?> type) {
    CtBinaryOperator<Object> b = factory.Core().createBinaryOperator();
    b.setKind(kind);
    b.setLeftHandOperand(l);
    b.setRightHandOperand(r);
    b.setType((CtTypeReference) cl(type));
    return b;
  }

  private CtUnaryOperator<Object> unaryOp(UnaryOperatorKind kind, CtExpression<?> operand) {
    CtUnaryOperator<Object> u = factory.Core().createUnaryOperator();
    u.setKind(kind);
    u.setOperand((CtExpression) operand);
    u.setType((CtTypeReference) (kind == UnaryOperatorKind.NOT ? booleanType()
        : cl(typeOrObject(operand))));
    return u;
  }

  private CtLiteral<Object> nullLiteral() {
    CtLiteral<Object> l = factory.Core().createLiteral();
    l.setValue(null);
    l.setType((CtTypeReference) factory.Type().nullType());
    return l;
  }

  // ---------------------------------------------------------------------------------------------
  // Lambdas et expressions conditionnelles
  // ---------------------------------------------------------------------------------------------

  private CtLambda<Object> lambda(KtLambdaExpression l, Ctx ctx) {
    CtLambda<Object> lam = factory.Core().createLambda();
    lam.setSimpleName("lambda$" + l.getTextRange().getStartOffset());
    lam.setType((CtTypeReference) objectType());
    Ctx x = ctx.copy();
    List<CtStatement> destructured = new ArrayList<>();
    List<KtParameter> params = l.getValueParameters();
    for (int i = 0; i < params.size(); i++) {
      KtParameter p = params.get(i);
      KtDestructuringDeclaration dd = p.getDestructuringDeclaration();
      if (dd == null) {
        CtParameter<Object> param = parameter(ctx.u, p, x);
        lam.addParameter(param);
        x.declare(param);
        continue;
      }
      CtParameter<Object> param = factory.Core().createParameter();
      param.setSimpleName("$destructured" + i);
      param.setType((CtTypeReference) objectType());
      markDecl(ctx.u, p, param);
      lam.addParameter(param);
      destructureInto(dd, variableRead(param), x, destructured);
    }
    if (params.isEmpty() && usesIt(l)) {
      CtParameter<Object> it = factory.Core().createParameter();
      it.setSimpleName("it");
      it.setType((CtTypeReference) objectType());
      mark(ctx.u, l, it);
      lam.addParameter(it);
      x.declare(it);
    }
    x.sink.addAll(destructured);
    KtBlockExpression body = l.getBodyExpression();
    List<KtExpression> st = body == null ? List.of() : body.getStatements();
    for (int i = 0; i < st.size(); i++) {
      KtExpression s = st.get(i);
      boolean last = i == st.size() - 1;
      if (last && isValue(s) && !(KtPsiUtil.safeDeparenthesize(s) instanceof KtIfExpression)
          && !(KtPsiUtil.safeDeparenthesize(s) instanceof KtWhenExpression)
          && !(KtPsiUtil.safeDeparenthesize(s) instanceof KtTryExpression)) {
        CtExpression<?> v = expr(s, x);
        CtReturn<Object> r = factory.Core().createReturn();
        r.setReturnedExpression((CtExpression) v);
        mark(ctx.u, s, r);
        x.sink.add(r);
      } else {
        statement(s, x);
      }
    }
    lam.setBody(block(x.sink));
    return lam;
  }

  /** Vrai si {@code it} est utilisé et désigne le paramètre implicite de cette lambda. */
  private static boolean usesIt(KtLambdaExpression l) {
    if (l.getBodyExpression() == null) {
      return false;
    }
    for (KtNameReferenceExpression n : PsiTreeUtil.collectElementsOfType(l.getBodyExpression(),
        KtNameReferenceExpression.class)) {
      if ("it".equals(n.getReferencedName())
          && PsiTreeUtil.getParentOfType(n, KtLambdaExpression.class) == l) {
        return true;
      }
    }
    return false;
  }

  /** Expression porteuse d'une valeur (par opposition aux sauts, déclarations et affectations). */
  private static boolean isValue(KtExpression e) {
    KtExpression d = KtPsiUtil.safeDeparenthesize(e);
    if (d instanceof KtLabeledExpression l) {
      d = l.getBaseExpression();
    }
    return d != null && !(d instanceof KtReturnExpression || d instanceof KtThrowExpression
        || d instanceof KtBreakExpression || d instanceof KtContinueExpression
        || d instanceof KtDeclaration || d instanceof KtLoopExpression
        || d instanceof KtDestructuringDeclaration
        || (d instanceof KtBinaryExpression b && isAssignment(b)));
  }

  /** Branche d'un if/when : instructions préalables et valeur (null si la branche saute). */
  private Branch branch(KtExpression e, Ctx ctx) {
    Ctx x = ctx.copy();
    CtExpression<?> value = null;
    KtExpression d = KtPsiUtil.safeDeparenthesize(e);
    if (d instanceof KtBlockExpression b) {
      List<KtExpression> st = b.getStatements();
      for (int i = 0; i < st.size(); i++) {
        if (i == st.size() - 1 && isValue(st.get(i))) {
          value = expr(st.get(i), x);
        } else {
          statement(st.get(i), x);
        }
      }
    } else if (d != null) {
      if (isValue(d)) {
        value = expr(d, x);
      } else {
        statement(d, x);
      }
    }
    return new Branch(x.sink, value);
  }

  /**
   * Choix entre branches : conditionnelle imbriquée si chaque branche est une simple valeur, sinon
   * chaîne de {@code if} ajoutée au puits (la valeur devient alors inconnue).
   */
  private CtExpression<?> choose(List<CtExpression<?>> conds, List<String> texts,
      List<Branch> branches, Branch otherwise, Ctx ctx) {
    boolean simple = otherwise != null && otherwise.prefix().isEmpty()
        && otherwise.value() != null;
    for (Branch b : branches) {
      simple &= b.prefix().isEmpty() && b.value() != null;
    }
    if (simple) {
      CtExpression<?> acc = otherwise.value();
      for (int i = branches.size() - 1; i >= 0; i--) {
        CtConditional<Object> c = factory.Core().createConditional();
        c.setCondition((CtExpression) condition(conds.get(i), texts.get(i)));
        c.setThenExpression((CtExpression) branches.get(i).value());
        c.setElseExpression((CtExpression) acc);
        c.setType((CtTypeReference) cl(typeOrObject(branches.get(i).value())));
        acc = c;
      }
      return acc;
    }
    CtStatement chain = otherwise == null ? null : branchBlock(otherwise);
    for (int i = branches.size() - 1; i >= 0; i--) {
      CtIf s = factory.Core().createIf();
      s.setCondition((CtExpression) condition(conds.get(i), texts.get(i)));
      s.setThenStatement(branchBlock(branches.get(i)));
      if (chain != null) {
        s.setElseStatement(chain);
      }
      chain = s;
    }
    if (chain != null) {
      ctx.sink.add(chain);
    }
    return null;
  }

  private CtExpression<?> condition(CtExpression<?> c, String text) {
    return c != null ? c : boolSnippet(text);
  }

  private CtBlock<?> branchBlock(Branch b) {
    List<CtStatement> out = new ArrayList<>(b.prefix());
    emit(b.value(), out);
    return block(out);
  }

  private CtExpression<?> whenExpr(KtWhenExpression w, Ctx ctx) {
    Supplier<CtExpression<?>> subj = subject(w, ctx);
    List<CtExpression<?>> conds = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    List<Branch> branches = new ArrayList<>();
    Branch otherwise = null;
    for (KtWhenEntry en : w.getEntries()) {
      if (en.isElse()) {
        otherwise = branch(en.getExpression(), ctx);
        continue;
      }
      conds.add(whenCondition(en, subj, ctx));
      texts.add(en.getText());
      branches.add(branch(en.getExpression(), ctx));
    }
    return choose(conds, texts, branches, otherwise, ctx);
  }

  /** Sujet d'un when : relu à chaque condition (variable locale si l'expression est complexe). */
  private Supplier<CtExpression<?>> subject(KtWhenExpression w, Ctx ctx) {
    KtProperty sv = w.getSubjectVariable();
    if (sv != null) {
      localProperty(sv, ctx);
      CtVariable<?> v = ctx.lookup(sv.getName());
      return v == null ? null : () -> variableRead(v);
    }
    KtExpression s = KtPsiUtil.safeDeparenthesize(w.getSubjectExpression());
    if (s == null) {
      return null;
    }
    if (s instanceof KtSimpleNameExpression || s instanceof KtConstantExpression
        || s instanceof KtThisExpression) {
      return () -> expr(s, ctx);
    }
    CtExpression<?> init = expr(s, ctx);
    CtLocalVariable<Object> lv = factory.Core().createLocalVariable();
    lv.setSimpleName("subject$" + s.getTextRange().getStartOffset());
    lv.setType((CtTypeReference) cl(typeOrObject(init)));
    lv.setDefaultExpression((CtExpression) init);
    lv.addModifier(ModifierKind.FINAL);
    mark(ctx.u, s, lv);
    ctx.sink.add(lv);
    ctx.declare(lv);
    return () -> variableRead(lv);
  }

  private CtExpression<?> whenCondition(KtWhenEntry en, Supplier<CtExpression<?>> subj,
      Ctx ctx) {
    CtExpression<?> acc = null;
    for (KtWhenCondition c : en.getConditions()) {
      CtExpression<?> one;
      if (c instanceof KtWhenConditionWithExpression we) {
        CtExpression<?> v = expr(we.getExpression(), ctx);
        one = subj == null ? v : binOp(BinaryOperatorKind.EQ, subj.get(), v, booleanType());
      } else if (c instanceof KtWhenConditionIsPattern ip && subj != null) {
        one = binOp(BinaryOperatorKind.INSTANCEOF, subj.get(),
            typeAccess(resolveType(ip.getTypeReference(), ctx), false), booleanType());
        if (ip.isNegated()) {
          one = unaryOp(UnaryOperatorKind.NOT, one);
        }
      } else if (c instanceof KtWhenConditionInRange ir && subj != null) {
        CtExpression<?> range = expr(ir.getRangeExpression(), ctx);
        List<CtExpression<?>> args = new ArrayList<>(List.of(subj.get()));
        one = invocation(range, execRef(typeOrObject(range), false, booleanType(), "contains",
            args), args);
        if (ir.isNegated()) {
          one = unaryOp(UnaryOperatorKind.NOT, one);
        }
      } else {
        one = boolSnippet(c.getText());
      }
      mark(ctx.u, c, one);
      acc = acc == null ? one : binOp(BinaryOperatorKind.OR, acc, one, booleanType());
    }
    return acc;
  }

  // ---------------------------------------------------------------------------------------------
  // Instructions
  // ---------------------------------------------------------------------------------------------

  /** Corps d'une fonction ou d'un accesseur ; {@code returns} : corps-expression renvoyé. */
  private CtBlock<?> body(KtDeclarationWithBody d, CtExecutable<?> exec, Ctx ctx,
      boolean returns) {
    Ctx x = ctx.copy();
    for (CtParameter<?> p : exec.getParameters()) {
      x.declare(p);
    }
    KtExpression b = d.getBodyExpression();
    if (b instanceof KtBlockExpression blk && d.hasBlockBody()) {
      statements(blk, x);
    } else if (b != null) {
      if (returns && isValue(b)) {
        CtExpression<?> v = expr(b, x);
        CtReturn<Object> r = factory.Core().createReturn();
        r.setReturnedExpression((CtExpression) v);
        mark(x.u, b, r);
        x.sink.add(r);
      } else {
        statement(b, x);
      }
    }
    return block(x.sink);
  }

  private void statements(KtBlockExpression b, Ctx ctx) {
    for (KtExpression s : b.getStatements()) {
      statement(s, ctx);
    }
  }

  /** Traduit une instruction dans le puits du contexte ; snippet en cas d'échec. */
  private void statement(KtExpression e, Ctx ctx) {
    if (e == null) {
      return;
    }
    int before = ctx.sink.size();
    try {
      statement0(e, ctx);
    } catch (RuntimeException | StackOverflowError ex) {
      ctx.sink.subList(before, ctx.sink.size()).clear();
      ctx.sink.add(snippetStatement(e.getText()));
    }
    for (int i = before; i < ctx.sink.size(); i++) {
      CtStatement s = ctx.sink.get(i);
      if (s.getMetadata(Provenance.META_FILE) == null) {
        mark(ctx.u, e, s);
      }
    }
  }

  private void statement0(KtExpression e, Ctx ctx) {
    if (e instanceof KtLabeledExpression l) {
      statement(l.getBaseExpression(), ctx);
    } else if (e instanceof KtAnnotatedExpression a) {
      statement(a.getBaseExpression(), ctx);
    } else if (e instanceof KtParenthesizedExpression p) {
      statement(p.getExpression(), ctx);
    } else if (e instanceof KtProperty p) {
      localProperty(p, ctx);
    } else if (e instanceof KtDestructuringDeclaration dd) {
      CtExpression<?> init = expr(dd.getInitializer(), ctx);
      CtLocalVariable<Object> tmp = factory.Core().createLocalVariable();
      tmp.setSimpleName("$destructured" + dd.getTextRange().getStartOffset());
      tmp.setType((CtTypeReference) cl(typeOrObject(init)));
      tmp.setDefaultExpression((CtExpression) init);
      tmp.addModifier(ModifierKind.FINAL);
      mark(ctx.u, dd, tmp);
      ctx.sink.add(tmp);
      ctx.declare(tmp);
      destructureInto(dd, variableRead(tmp), ctx, ctx.sink);
    } else if (e instanceof KtNamedFunction fn) {
      localFunction(fn, ctx);
    } else if (e instanceof KtClassOrObject || e instanceof KtTypeAlias) {
      info(ctx.u, e, ctx.type.getQualifiedName(), "Déclaration Kotlin locale non traduite : "
          + kindOf((KtDeclaration) e));
    } else if (e instanceof KtReturnExpression r) {
      CtReturn<Object> ret = factory.Core().createReturn();
      if (r.getReturnedExpression() != null) {
        ret.setReturnedExpression((CtExpression) expr(r.getReturnedExpression(), ctx));
      }
      ctx.sink.add(ret);
    } else if (e instanceof KtThrowExpression t) {
      CtThrow th = factory.Core().createThrow();
      th.setThrownExpression((CtExpression) expr(t.getThrownExpression(), ctx));
      ctx.sink.add(th);
    } else if (e instanceof KtBreakExpression) {
      ctx.sink.add(factory.Core().createBreak());
    } else if (e instanceof KtContinueExpression) {
      ctx.sink.add(factory.Core().createContinue());
    } else if (e instanceof KtIfExpression i) {
      CtIf s = factory.Core().createIf();
      s.setCondition((CtExpression) expr(i.getCondition(), ctx));
      s.setThenStatement(blockOf(i.getThen(), ctx));
      if (i.getElse() != null) {
        s.setElseStatement(blockOf(i.getElse(), ctx));
      }
      ctx.sink.add(s);
    } else if (e instanceof KtWhenExpression w) {
      whenStatement(w, ctx);
    } else if (e instanceof KtTryExpression t) {
      ctx.sink.add(tryStatement(t, ctx));
    } else if (e instanceof KtForExpression f) {
      forStatement(f, ctx);
    } else if (e instanceof KtWhileExpression w) {
      CtWhile s = factory.Core().createWhile();
      s.setLoopingExpression((CtExpression) expr(w.getCondition(), ctx));
      s.setBody(blockOf(w.getBody(), ctx));
      ctx.sink.add(s);
    } else if (e instanceof KtDoWhileExpression w) {
      CtDo s = factory.Core().createDo();
      s.setBody(blockOf(w.getBody(), ctx));
      s.setLoopingExpression((CtExpression) expr(w.getCondition(), ctx));
      ctx.sink.add(s);
    } else if (e instanceof KtBinaryExpression b && isAssignment(b)) {
      assignment(b, ctx);
    } else if (e instanceof KtBlockExpression b) {
      ctx.sink.add(blockOf(b, ctx));
    } else {
      emit(expr(e, ctx), ctx.sink);
    }
  }

  /** Bloc traduit dans une portée fille. */
  private CtBlock<?> blockOf(KtExpression e, Ctx ctx) {
    Ctx x = ctx.copy();
    KtExpression d = KtPsiUtil.safeDeparenthesize(e);
    if (d instanceof KtBlockExpression b) {
      statements(b, x);
    } else if (d != null) {
      statement(d, x);
    }
    return block(x.sink);
  }

  private void whenStatement(KtWhenExpression w, Ctx ctx) {
    Supplier<CtExpression<?>> subj = subject(w, ctx);
    // Traduction dans l'ordre du source, chaînage ensuite
    List<CtIf> ifs = new ArrayList<>();
    CtStatement otherwise = null;
    for (KtWhenEntry en : w.getEntries()) {
      if (en.isElse()) {
        otherwise = blockOf(en.getExpression(), ctx);
        continue;
      }
      CtIf s = factory.Core().createIf();
      mark(ctx.u, en, s);
      s.setCondition((CtExpression) whenCondition(en, subj, ctx));
      s.setThenStatement(blockOf(en.getExpression(), ctx));
      ifs.add(s);
    }
    CtStatement chain = otherwise;
    for (int i = ifs.size() - 1; i >= 0; i--) {
      if (chain != null) {
        ifs.get(i).setElseStatement(chain);
      }
      chain = ifs.get(i);
    }
    if (chain != null) {
      ctx.sink.add(chain);
    }
  }

  private CtTry tryStatement(KtTryExpression t, Ctx ctx) {
    CtTry tr = factory.Core().createTry();
    tr.setBody(blockOf(t.getTryBlock(), ctx));
    for (KtCatchClause cc : t.getCatchClauses()) {
      CtCatch c = factory.Core().createCatch();
      Ctx x = ctx.copy();
      KtParameter p = cc.getCatchParameter();
      CtCatchVariable<Object> cv = factory.Core().createCatchVariable();
      cv.setSimpleName(p != null && p.getName() != null ? p.getName() : "e");
      cv.setType((CtTypeReference) (p != null ? resolveType(p.getTypeReference(), ctx)
          : factory.Type().createReference(Throwable.class)));
      if (p != null) {
        markDecl(ctx.u, p, cv);
      }
      c.setParameter((CtCatchVariable) cv);
      x.declare(cv);
      c.setBody(blockOf(cc.getCatchBody(), x));
      mark(ctx.u, cc, c);
      tr.addCatcher(c);
    }
    KtFinallySection fs = t.getFinallyBlock();
    if (fs != null && fs.getFinalExpression() != null) {
      tr.setFinalizer(blockOf(fs.getFinalExpression(), ctx));
    }
    mark(ctx.u, t, tr);
    return tr;
  }

  private void forStatement(KtForExpression f, Ctx ctx) {
    CtForEach fe = factory.Core().createForEach();
    CtExpression<?> range = expr(f.getLoopRange(), ctx);
    Ctx x = ctx.copy();
    KtParameter p = f.getLoopParameter();
    CtLocalVariable<Object> v = factory.Core().createLocalVariable();
    KtDestructuringDeclaration dd = f.getDestructuringDeclaration();
    if (dd == null && p != null && p.getName() != null) {
      v.setSimpleName(p.getName());
      v.setType((CtTypeReference) (p.getTypeReference() != null
          ? resolveType(p.getTypeReference(), ctx) : objectType()));
      markDecl(ctx.u, p, v);
    } else {
      v.setSimpleName("$destructured" + f.getTextRange().getStartOffset());
      v.setType((CtTypeReference) objectType());
      mark(ctx.u, f, v);
    }
    fe.setVariable(v);
    fe.setExpression((CtExpression) range);
    x.declare(v);
    if (dd != null) {
      destructureInto(dd, variableRead(v), x, x.sink);
    }
    KtExpression body = KtPsiUtil.safeDeparenthesize(f.getBody());
    if (body instanceof KtBlockExpression b) {
      statements(b, x);
    } else {
      statement(body, x);
    }
    fe.setBody(block(x.sink));
    ctx.sink.add(fe);
  }

  /** {@code val (a, b) = src} : variables locales {@code a = src.component1()}, etc. */
  private void destructureInto(KtDestructuringDeclaration dd, CtExpression<?> src, Ctx ctx,
      List<CtStatement> out) {
    List<KtDestructuringDeclarationEntry> entries = dd.getEntries();
    for (int i = 0; i < entries.size(); i++) {
      KtDestructuringDeclarationEntry en = entries.get(i);
      if (en.getName() == null || "_".equals(en.getName())) {
        continue;
      }
      CtExpression<?> recv = src.clone();
      String comp = "component" + (i + 1);
      CtLocalVariable<Object> lv = factory.Core().createLocalVariable();
      lv.setSimpleName(en.getName());
      lv.setType((CtTypeReference) (en.getTypeReference() != null
          ? resolveType(en.getTypeReference(), ctx) : objectType()));
      lv.setDefaultExpression((CtExpression) invocation(recv, execRef(typeOrObject(recv), false,
          objectType(), comp, List.of()), List.of()));
      if (!en.isVar()) {
        lv.addModifier(ModifierKind.FINAL);
      }
      markDecl(ctx.u, en, lv);
      out.add(lv);
      ctx.declare(lv);
    }
  }

  /** Fonction locale : variable locale portant une lambda (les appels deviennent invoke). */
  private void localFunction(KtNamedFunction fn, Ctx ctx) {
    if (fn.getName() == null) {
      return;
    }
    CtLocalVariable<Object> lv = factory.Core().createLocalVariable();
    lv.setSimpleName(fn.getName());
    lv.setType((CtTypeReference) objectType());
    lv.addModifier(ModifierKind.FINAL);
    markDecl(ctx.u, fn, lv);
    ctx.declare(lv);
    CtLambda<Object> lam = factory.Core().createLambda();
    lam.setSimpleName(fn.getName());
    lam.setType((CtTypeReference) objectType());
    Ctx x = ctx.copy();
    for (KtParameter p : fn.getValueParameters()) {
      lam.addParameter(parameter(ctx.u, p, x));
    }
    KtTypeReference tr = fn.getTypeReference();
    boolean returns = fn.hasBody() && !fn.hasBlockBody() && (tr == null || !isUnit(tr));
    lam.setBody((CtBlock) body(fn, lam, x, returns));
    mark(ctx.u, fn, lam);
    lv.setDefaultExpression((CtExpression) lam);
    ctx.sink.add(lv);
  }

  private void localProperty(KtProperty p, Ctx ctx) {
    if (p.getName() == null) {
      return;
    }
    KtExpression init = p.getInitializer();
    if (init == null && p.getDelegate() != null) {
      init = p.getDelegate().getExpression();
    }
    CtExpression<?> value = init == null ? null : expr(init, ctx);
    CtTypeReference<?> type = p.getTypeReference() != null ? resolveType(p.getTypeReference(), ctx)
        : value != null && value.getType() != null && !(value instanceof CtCodeSnippetExpression)
            ? cl(value.getType()) : inferType(init, ctx);
    CtLocalVariable<Object> lv = factory.Core().createLocalVariable();
    lv.setSimpleName(p.getName());
    lv.setType((CtTypeReference) type);
    if (value != null) {
      lv.setDefaultExpression((CtExpression) value);
    }
    if (!p.isVar()) {
      lv.addModifier(ModifierKind.FINAL);
    }
    nullable(lv, type);
    markDecl(ctx.u, p, lv);
    comments(p, lv);
    for (KtAnnotationEntry e : p.getAnnotationEntries()) {
      CtAnnotation<?> a = annotation(e, ctx.copy());
      if (a != null) {
        lv.addAnnotation(a);
      }
    }
    ctx.sink.add(lv);
    ctx.declare(lv);
  }

  private void assignment(KtBinaryExpression b, Ctx ctx) {
    IElementType op = b.getOperationReference().getReferencedNameElementType();
    KtExpression left = KtPsiUtil.safeDeparenthesize(b.getLeft());
    if (left instanceof KtArrayAccessExpression aa) {
      if (op != KtTokens.EQ) {
        ctx.sink.add(snippetStatement(b.getText()));
        return;
      }
      CtExpression<?> recv = expr(aa.getArrayExpression(), ctx);
      List<CtExpression<?>> args = new ArrayList<>();
      for (KtExpression i : aa.getIndexExpressions()) {
        args.add(expr(i, ctx));
      }
      args.add(expr(b.getRight(), ctx));
      ctx.sink.add(invocation(recv, execRef(typeOrObject(recv), false,
          factory.Type().voidPrimitiveType(), "set", args), args));
      return;
    }
    CtExpression<?> target = writeAccess(left, ctx);
    if (target == null) {
      ctx.sink.add(snippetStatement(b.getText()));
      return;
    }
    CtExpression<?> value = expr(b.getRight(), ctx);
    CtAssignment<Object, Object> a;
    if (op == KtTokens.EQ) {
      a = factory.Core().createAssignment();
    } else {
      CtOperatorAssignment<Object, Object> oa = factory.Core().createOperatorAssignment();
      oa.setKind(COMPOUND_KINDS.get(op));
      a = oa;
    }
    a.setAssigned((CtExpression) target);
    a.setAssignment((CtExpression) value);
    a.setType((CtTypeReference) cl(typeOrObject(target)));
    mark(ctx.u, b, a);
    ctx.sink.add(a);
  }

  /** Accès en écriture à une variable ou un champ, sinon null. */
  private CtExpression<?> writeAccess(KtExpression e, Ctx ctx) {
    KtExpression d = KtPsiUtil.safeDeparenthesize(e);
    if (d instanceof KtSimpleNameExpression n) {
      String id = n.getReferencedName();
      CtVariable<?> v = ctx.lookup(id);
      if (v != null && !(v instanceof CtField)) {
        return factory.Code().createVariableWrite((CtVariableReference) v.getReference(), false);
      }
      CtField<?> f = v instanceof CtField<?> vf ? vf : findField(ctx, id);
      if (f == null) {
        f = topLevelProperty(ctx.u.pkg, id);
      }
      CtFieldWrite<Object> fw = factory.Core().createFieldWrite();
      if (f != null) {
        CtFieldReference<?> ref = f.getReference();
        boolean isStatic = f.isStatic();
        ref.setStatic(isStatic);
        fw.setVariable((CtFieldReference) ref);
        fw.setTarget(isStatic ? (CtExpression) typeAccess(f.getDeclaringType().getReference(), true)
            : thisAccess(f.getDeclaringType(), true));
      } else {
        CtFieldReference<Object> ref = factory.Field().createReference(ctx.type.getReference(),
            objectType(), id);
        ref.setStatic(ctx.staticCtx);
        fw.setVariable(ref);
        fw.setTarget((CtExpression) implicitThis(ctx));
      }
      return fw;
    }
    if (d instanceof KtQualifiedExpression) {
      CtExpression<?> r = expr(d, ctx);
      if (r instanceof CtFieldRead<?> fr) {
        CtFieldWrite<Object> fw = factory.Core().createFieldWrite();
        fw.setVariable((CtFieldReference) fr.getVariable().clone());
        if (fr.getTarget() != null) {
          fw.setTarget((CtExpression) fr.getTarget().clone());
        }
        return fw;
      }
    }
    return null;
  }

  /**
   * Ajoute une expression comme instruction : telle quelle si c'en est une, sinon ses
   * sous-expressions porteuses d'effets (appels) ; les valeurs pures sont abandonnées.
   */
  private void emit(CtExpression<?> e, List<CtStatement> out) {
    if (e == null) {
      return;
    }
    if (e instanceof CtStatement s) {
      out.add(s);
    } else if (e instanceof CtCodeSnippetExpression<?> cs) {
      CtCodeSnippetStatement st = snippetStatement(cs.getValue());
      copyMeta(e, st);
      out.add(st);
    } else if (e instanceof CtBinaryOperator<?> b) {
      emit(b.getLeftHandOperand(), out);
      emit(b.getRightHandOperand(), out);
    } else if (e instanceof CtUnaryOperator<?> u) {
      emit(u.getOperand(), out);
    } else if (e instanceof CtConditional<?> c) {
      CtIf s = factory.Core().createIf();
      s.setCondition((CtExpression) c.getCondition());
      List<CtStatement> th = new ArrayList<>();
      emit(c.getThenExpression(), th);
      s.setThenStatement(block(th));
      List<CtStatement> el = new ArrayList<>();
      emit(c.getElseExpression(), el);
      s.setElseStatement(block(el));
      copyMeta(e, s);
      out.add(s);
    } else if (e instanceof CtFieldRead<?> fr) {
      if (fr.getTarget() != null && !(fr.getTarget() instanceof CtTypeAccess)
          && !(fr.getTarget() instanceof CtThisAccess)) {
        emit(fr.getTarget(), out);
      }
    } else if (e instanceof CtNewArray<?> na) {
      for (CtExpression<?> x : new ArrayList<>(na.getElements())) {
        emit(x, out);
      }
    }
  }

  private static void copyMeta(CtElement from, CtElement to) {
    for (String k : List.of(Provenance.META_FILE, Provenance.META_LINE, META_OFFSET)) {
      Object v = from.getMetadata(k);
      if (v != null) {
        to.putMetadata(k, v);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Fabriques et inférence
  // ---------------------------------------------------------------------------------------------

  private CtBlock<Object> block(List<CtStatement> statements) {
    CtBlock<Object> b = factory.Core().createBlock();
    for (CtStatement s : statements) {
      b.addStatement(s);
    }
    return b;
  }

  private CtStatementList statementList(List<CtStatement> statements) {
    CtStatementList l = factory.Core().createStatementList();
    for (CtStatement s : statements) {
      l.addStatement(s);
    }
    return l;
  }

  private CtCodeSnippetExpression<Object> snippet(String code) {
    CtCodeSnippetExpression<Object> s = factory.Code().createCodeSnippetExpression(code);
    s.setType((CtTypeReference) objectType());
    return s;
  }

  private CtCodeSnippetExpression<Boolean> boolSnippet(String code) {
    CtCodeSnippetExpression<Boolean> s = factory.Code().createCodeSnippetExpression(code);
    s.setType(factory.Type().booleanPrimitiveType());
    return s;
  }

  private CtCodeSnippetStatement snippetStatement(String code) {
    return factory.Code().createCodeSnippetStatement(code);
  }

  private CtExpression<?> variableRead(CtVariable<?> v) {
    return factory.Code().createVariableRead((CtVariableReference) v.getReference(), false);
  }

  private CtTypeReference<Object> objectType() {
    return factory.Type().objectType();
  }

  private CtTypeReference<Boolean> booleanType() {
    return factory.Type().booleanPrimitiveType();
  }

  private CtTypeReference<?> typeOrObject(CtExpression<?> e) {
    return e != null && e.getType() != null ? e.getType() : objectType();
  }

  private CtThisAccess<Object> thisAccess(CtType<?> t, boolean implicit) {
    return factory.Code().createThisAccess((CtTypeReference) t.getReference(), implicit);
  }

  private CtTypeAccess<Object> typeAccess(CtTypeReference<?> ref, boolean implicit) {
    return factory.Code().createTypeAccess((CtTypeReference) ref, implicit);
  }

  /** Type déduit syntaxiquement (sans effet de bord) d'un initialiseur ou d'un corps-expression. */
  private CtTypeReference<?> inferType(KtExpression e, Ctx ctx) {
    KtExpression d = KtPsiUtil.safeDeparenthesize(e);
    try {
      if (d instanceof KtStringTemplateExpression) {
        return factory.Type().stringType();
      }
      if (d instanceof KtConstantExpression c) {
        CtExpression<?> lit = constant(c);
        return lit == null || lit.getType() == null ? objectType() : lit.getType().box();
      }
      if (d instanceof KtBinaryExpressionWithTypeRHS b && b.getRight() != null) {
        return resolveType(b.getRight(), ctx);
      }
      if (d instanceof KtIsExpression) {
        return factory.Type().createReference(Boolean.class);
      }
      if (d instanceof KtBinaryExpression b) {
        BinaryOperatorKind k = BINARY_KINDS.get(
            b.getOperationReference().getReferencedNameElementType());
        if (k != null && k != BinaryOperatorKind.PLUS && k != BinaryOperatorKind.MINUS
            && k != BinaryOperatorKind.MUL && k != BinaryOperatorKind.DIV
            && k != BinaryOperatorKind.MOD) {
          return factory.Type().createReference(Boolean.class);
        }
        if (k == BinaryOperatorKind.PLUS) {
          CtTypeReference<?> l = inferType(b.getLeft(), ctx);
          return "java.lang.String".equals(l.getQualifiedName()) ? l
              : inferType(b.getRight(), ctx);
        }
        return inferType(b.getLeft(), ctx);
      }
      if (d instanceof KtCallExpression c) {
        String name = calleeName(c);
        if (name == null) {
          return objectType();
        }
        if (ARRAY_FACTORIES.contains(name)) {
          return factory.Type().createArrayReference(objectType());
        }
        if (isUpper(name)) {
          CtTypeReference<?> t = knownType(name, ctx, false);
          return t != null ? t : objectType();
        }
        return inferReturn(name);
      }
      if (d instanceof KtQualifiedExpression q
          && q.getSelectorExpression() instanceof KtCallExpression c && calleeName(c) != null) {
        return inferReturn(calleeName(c));
      }
    } catch (RuntimeException ex) {
      return objectType();
    }
    return objectType();
  }

  // ---------------------------------------------------------------------------------------------
  // Utilitaires
  // ---------------------------------------------------------------------------------------------

  private static Map<String, String> table(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return Collections.unmodifiableMap(m);
  }

  private static String qualify(String pkg, String name) {
    return pkg == null || pkg.isEmpty() ? name : pkg + "." + name;
  }

  private static String capitalize(String s) {
    return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static boolean isUpper(String s) {
    return s != null && !s.isEmpty() && Character.isUpperCase(s.charAt(0));
  }

  /** {@code MAX_SIZE} : au moins une lettre, aucune minuscule. */
  private static boolean isAllCaps(String s) {
    boolean letter = false;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (Character.isLowerCase(ch)) {
        return false;
      }
      letter |= Character.isLetter(ch);
    }
    return letter;
  }

  private static String lastSegment(String qn) {
    return qn.substring(qn.lastIndexOf('.') + 1);
  }

  private static String kindOf(KtDeclaration d) {
    return d.getClass().getSimpleName().replaceFirst("^Kt", "");
  }

  private static String getterName(String name) {
    if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
      return name;
    }
    return "get" + capitalize(name);
  }

  private static ModifierKind visibility(KtModifierListOwner o, ModifierKind def) {
    if (o.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
      return ModifierKind.PRIVATE;
    }
    if (o.hasModifier(KtTokens.PROTECTED_KEYWORD)) {
      return ModifierKind.PROTECTED;
    }
    return def;
  }

  private void info(FileUnit u, PsiElement psi, String cls, String message) {
    Integer line = psi == null ? null : u.line(psi.getTextRange().getStartOffset());
    diagnostics.info(UNSUPPORTED, message, Source.of(cls, u.rel, line));
  }

  private static String describe(Throwable e) {
    String m = e.getMessage();
    return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : " : " + m);
  }

  /** Provenance d'une déclaration : ligne de son nom. */
  private static void markDecl(FileUnit u, PsiElement psi, CtElement el) {
    int off = psi.getTextOffset();
    if (psi instanceof PsiNameIdentifierOwner o && o.getNameIdentifier() != null) {
      off = o.getNameIdentifier().getTextRange().getStartOffset();
    }
    put(u, el, off);
  }

  /** Provenance d'une expression ou d'une instruction : son début. */
  private static void mark(FileUnit u, PsiElement psi, CtElement el) {
    put(u, el, psi.getTextRange().getStartOffset());
  }

  private static void put(FileUnit u, CtElement el, int off) {
    el.putMetadata(Provenance.META_FILE, u.rel);
    el.putMetadata(Provenance.META_LINE, u.line(off));
    el.putMetadata(META_OFFSET, off);
  }

  private static void nullable(CtElement el, CtTypeReference<?> type) {
    if (type != null && Boolean.TRUE.equals(type.getMetadata(META_NULLABLE))) {
      el.putMetadata(META_NULLABLE, Boolean.TRUE);
    }
  }

  /** Commentaires précédant la déclaration (KDoc compris), rattachés à l'élément. */
  private void comments(PsiElement psi, CtElement el) {
    List<PsiComment> found = new ArrayList<>();
    for (PsiElement p = psi.getPrevSibling(); p != null; p = p.getPrevSibling()) {
      if (p instanceof PsiWhiteSpace w) {
        if (w.getText().chars().filter(ch -> ch == '\n').count() >= 2) {
          break;
        }
      } else if (p instanceof PsiComment c) {
        found.add(0, c);
      } else {
        break;
      }
    }
    for (PsiElement p = psi.getFirstChild(); p instanceof PsiComment || p instanceof PsiWhiteSpace;
        p = p.getNextSibling()) {
      if (p instanceof PsiComment c) {
        found.add(c);
      }
    }
    for (PsiComment c : found) {
      String text = c.getText();
      CtComment.CommentType type = text.startsWith("/**") ? CtComment.CommentType.JAVADOC
          : text.startsWith("/*") ? CtComment.CommentType.BLOCK : CtComment.CommentType.INLINE;
      try {
        el.addComment(factory.Code().createComment(cleanComment(text), type));
      } catch (RuntimeException e) {
        // commentaire ignoré : jamais bloquant
      }
    }
  }

  private static String cleanComment(String text) {
    String t = text;
    if (t.startsWith("/**")) {
      t = t.substring(3);
    } else if (t.startsWith("/*") || t.startsWith("//")) {
      t = t.substring(2);
    }
    if (t.endsWith("*/")) {
      t = t.substring(0, t.length() - 2);
    }
    StringBuilder sb = new StringBuilder();
    for (String line : t.split("\n", -1)) {
      String l = line.strip();
      if (l.startsWith("*")) {
        l = l.substring(1).strip();
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(l);
    }
    return sb.toString().strip();
  }

  /** Propage la provenance du porteur le plus proche aux éléments qui n'en ont pas. */
  private static void inheritMeta(CtType<?> t) {
    Deque<CtElement> carriers = new ArrayDeque<>();
    new CtScanner() {
      @Override
      protected void enter(CtElement e) {
        if (e instanceof CtReference) {
          return;
        }
        if (e.getMetadata(Provenance.META_FILE) == null && !carriers.isEmpty()) {
          copyMeta(carriers.peek(), e);
        }
        if (e.getMetadata(Provenance.META_FILE) != null) {
          carriers.push(e);
        }
      }

      @Override
      protected void exit(CtElement e) {
        if (!carriers.isEmpty() && carriers.peek() == e) {
          carriers.pop();
        }
      }
    }.scan(t);
  }
}
