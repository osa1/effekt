package effekt
package core


import effekt.PhaseResult.CoreTransformed

import scala.collection.mutable.ListBuffer
import effekt.context.{ Annotations, Context, ContextOps }
import effekt.symbols.builtins.*
import effekt.context.assertions.*

import effekt.core.Block.BlockLit
import effekt.core.Pure.ValueVar
import effekt.core.normal.*

import effekt.symbols.TmpValue
import effekt.util.messages.INTERNAL_ERROR

import scala.collection.mutable

import kiama.util.Counter

object Optimizer extends Phase[CoreTransformed, CoreTransformed] {

  val phaseName: String = "core-optimizer"

  def run(input: CoreTransformed)(using Context): Option[CoreTransformed] =
    input match {
      case CoreTransformed(source, tree, mod, core) =>
        Some(CoreTransformed(source, tree, mod, optimize(Context.checkMain(mod), core)))
    }

  def optimize(mainSymbol: symbols.Symbol, core: ModuleDecl)(using Context) =
     // (1) first thing we do is simply remove unused definitions (this speeds up all following analysis and rewrites)
    var tree = RemoveUnusedDefinitions(Set(mainSymbol), core).run()

    // (2) inline unique block definitions
    var lastCount = 1
    while (lastCount > 0) {
      val (inlined, count) = InlineUnique.once(Set(mainSymbol), tree)
      // (3) drop unused definitions after inlining
      tree = RemoveUnusedDefinitions(Set(mainSymbol), inlined).run()
      lastCount = count
    }
    tree
}

class RemoveUnusedDefinitions(entrypoints: Set[Id], m: ModuleDecl) extends core.Tree.Rewrite {

  val reachable = Reachable(entrypoints, m.definitions.map(d => d.id -> d).toMap)

  override def stmt = {
    // Remove local unused definitions
    case Scope(defs, stmt) =>
      scope(defs.collect {
        case d: Definition.Def if reachable.isDefinedAt(d.id) => rewrite(d)
        // we only keep non-pure OR reachable let bindings
        case d: Definition.Let if d.capt.nonEmpty || reachable.isDefinedAt(d.id) => rewrite(d)
      }, rewrite(stmt))
  }

  def run(): ModuleDecl = {
    m.copy(
      // Remove top-level unused definitions
      definitions = m.definitions.filter { d => reachable.isDefinedAt(d.id) }.map(rewrite),
      externs = m.externs.collect {
        case e: Extern.Def if reachable.isDefinedAt(e.id) => e
        case e: Extern.Include => e
      }
    )
  }
}

/**
 * Inlines block definitions that are only used exactly once.
 *
 * 1. First computes usage (using [[Reachable.apply]])
 * 2. Top down traversal where we inline unique definitions
 *
 * Invariants:
 *   - the context `defs` always contains the _original_ definitions, not rewritten ones.
 *     Rewriting them has to be performed at the inline-site.
 */
object InlineUnique {

  case class InlineContext(
    // is mutable to update when introducing temporaries;
    // they should also be visible after leaving a scope (so mutable.Map and not `var usage`).
    usage: mutable.Map[Id, Usage],
    defs: Map[Id, Definition],
    inlineCount: Counter = Counter(0)
  ) {
    def ++(other: Map[Id, Definition]): InlineContext = InlineContext(usage, defs ++ other)

    def ++=(fresh: Map[Id, Usage]): Unit = { usage ++= fresh }
  }

  def once(entrypoints: Set[Id], m: ModuleDecl): (ModuleDecl, Int) = {
    val usage = Reachable(m) ++ entrypoints.map(id => id -> Usage.Many).toMap
    val defs = m.definitions.map(d => d.id -> d).toMap

    val context = InlineContext(mutable.Map.from(usage), defs)

    val (updatedDefs, _) = rewrite(m.definitions)(using context)
    (m.copy(definitions = updatedDefs), context.inlineCount.value)
  }

  def shouldInline(id: Id)(using ctx: InlineContext): Boolean =
    ctx.usage.get(id) match {
      case None => false
      case Some(Usage.Once) => true
      case Some(Usage.Recursive) => false // we don't inline recursive functions for the moment
      case Some(Usage.Many) => false
    }

  def shouldKeep(id: Id)(using ctx: InlineContext): Boolean =
    ctx.usage.get(id) match {
      case None => false
      case Some(Usage.Once) => false
      case Some(Usage.Recursive) => true // we don't inline recursive functions for the moment
      case Some(Usage.Many) => true
    }

  def rewrite(definitions: List[Definition])(using ctx: InlineContext): (List[Definition], InlineContext) =
    given allDefs: InlineContext = ctx ++ definitions.map(d => d.id -> d).toMap

    val filtered = definitions.collect {
      case Definition.Def(id, block) => Definition.Def(id, rewrite(block))
      // we drop aliases
      case Definition.Let(id, binding) if !binding.isInstanceOf[ValueVar] =>
        Definition.Let(id, rewrite(binding))
    }
    (filtered, allDefs)

  def blockDefFor(id: Id)(using ctx: InlineContext): Option[Block] =
    ctx.defs.get(id) map {
      case Definition.Def(id, block) => rewrite(block)
      case Definition.Let(id, binding) => INTERNAL_ERROR("Should not happen")
    }

  def dealias(b: Block.BlockVar)(using ctx: InlineContext): BlockVar =
    ctx.defs.get(b.id) match {
      case Some(Definition.Def(id, aliased : Block.BlockVar)) => dealias(aliased)
      case _ => b
    }

  def dealias(b: Pure.ValueVar)(using ctx: InlineContext): ValueVar =
    ctx.defs.get(b.id) match {
      case Some(Definition.Let(id, aliased : Pure.ValueVar)) => dealias(aliased)
      case _ => b
    }

  def debug(s: Stmt): Unit = println(core.PrettyPrinter.format(s))
  def debug(s: Block): Unit = println(core.PrettyPrinter.format(s))

  def rewrite(d: Definition)(using InlineContext): Definition = d match {
    case Definition.Def(id, block) => Definition.Def(id, rewrite(block))
    case Definition.Let(id, binding) => Definition.Let(id, rewrite(binding))
  }

  def rewrite(s: Stmt)(using InlineContext): Stmt = s match {
    case Stmt.Scope(definitions, body) =>
      val (defs, ctx) = rewrite(definitions)
      scope(defs, rewrite(body)(using ctx))

    case Stmt.App(b, targs, vargs, bargs) =>
      app(rewrite(b), targs, vargs.map(rewrite), bargs.map(rewrite))

    // congruences
    case Stmt.Return(expr) => Return(rewrite(expr))
    case Stmt.Val(id, binding, body) => valDef(id, rewrite(binding), rewrite(body))
    case Stmt.If(cond, thn, els) => If(rewrite(cond), rewrite(thn), rewrite(els))
    case Stmt.Match(scrutinee, clauses, default) =>
      patternMatch(rewrite(scrutinee), clauses.map { case (id, value) => id -> rewrite(value) }, default.map(rewrite))
    case Stmt.Alloc(id, init, region, body) => Alloc(id, rewrite(init), region, rewrite(body))
    case Stmt.Try(body, handlers) => Try(rewrite(body), handlers.map(rewrite))
    case Stmt.Region(body) => Region(rewrite(body))
    case Stmt.Var(id, init, capture, body) => Stmt.Var(id, rewrite(init), capture, rewrite(body))
    case Stmt.Get(id, capt, tpe) => Stmt.Get(id, capt, tpe)
    case Stmt.Put(id, capt, value) => Stmt.Put(id, capt, rewrite(value))
    case Stmt.Hole() => s
  }
  def rewrite(b: BlockLit)(using InlineContext): BlockLit =
    b match {
      case BlockLit(tparams, cparams, vparams, bparams, body) =>
        BlockLit(tparams, cparams, vparams, bparams, rewrite(body))
    }

  def rewrite(b: Block)(using C: InlineContext): Block = b match {
    case Block.BlockVar(id, _, _) if shouldInline(id) =>
      blockDefFor(id) match {
        case Some(value) =>
          C.inlineCount.next()
          //println(s"Inlining: ${id}")
          val renamed = Renamer.rename(value)
          //          debug(renamed)
          //          debug(value)
          renamed
        case None => b
      }
    case b @ Block.BlockVar(id, _, _) => dealias(b)

    // congruences
    case b @ Block.BlockLit(tparams, cparams, vparams, bparams, body) => rewrite(b)
    case Block.Member(block, field, annotatedTpe) => member(rewrite(block), field, annotatedTpe)
    case Block.Unbox(pure) => unbox(rewrite(pure))
    case Block.New(impl) => New(rewrite(impl))
  }

  def rewrite(s: Implementation)(using InlineContext): Implementation =
    s match {
      case Implementation(interface, operations) => Implementation(interface, operations.map { op =>
        op.copy(body = rewrite(op.body))
      })
    }

  def rewrite(p: Pure)(using InlineContext): Pure = p match {
    case Pure.PureApp(b, targs, vargs) => pureApp(rewrite(b), targs, vargs.map(rewrite))
    // currently, we don't inline values, but we can dealias them
    case x @ Pure.ValueVar(id, annotatedType) => dealias(x)

    // congruences
    case Pure.Literal(value, annotatedType) => p
    case Pure.Select(target, field, annotatedType) => select(rewrite(target), field, annotatedType)
    case Pure.Box(b, annotatedCapture) => box(rewrite(b), annotatedCapture)
  }

  def rewrite(e: Expr)(using InlineContext): Expr = e match {
    case DirectApp(b, targs, vargs, bargs) => directApp(rewrite(b), targs, vargs.map(rewrite), bargs.map(rewrite))

    // congruences
    case Run(s) => run(rewrite(s))
    case pure: Pure => rewrite(pure)
  }

  case class Binding[A](run: (A => Stmt) => Stmt) {
    def flatMap[B](rest: A => Binding[B]): Binding[B] = {
      Binding(k => run(a => rest(a).run(k)))
    }
  }

  def pure[A](a: A): Binding[A] = Binding(k => k(a))

}

/**
 * A simple reachability analysis for toplevel definitions
 *
 * TODO this could also be extended to cover record and interface declarations.
 */
class Reachable(
  var reachable: Map[Id, Usage],
  var stack: List[Id],
  var seen: Set[Id]
) {

  def within(id: Id)(f: => Unit): Unit = {
    stack = id :: stack
    f
    stack = stack.tail
  }

  def process(d: Definition)(using defs: Map[Id, Definition]): Unit =
    if stack.contains(d.id) then
      reachable = reachable.updated(d.id, Usage.Recursive)
    else d match {
      case Definition.Def(id, block) =>
        seen = seen + id
        within(id) { process(block) }

      case Definition.Let(id, binding) =>
        seen = seen + id
        process(binding)
    }

  def process(id: Id)(using defs: Map[Id, Definition]): Unit =
    if (stack.contains(id)) {
      reachable = reachable.updated(id, Usage.Recursive)
      return;
    }

    val count = reachable.get(id) match {
      case Some(Usage.Once) => Usage.Many
      case Some(Usage.Many) => Usage.Many
      case Some(Usage.Recursive) => Usage.Recursive
      case None => Usage.Once
    }
    reachable = reachable.updated(id, count)
    if (!seen.contains(id)) {
      defs.get(id).foreach(process)
    }

  def process(b: Block)(using defs: Map[Id, Definition]): Unit =
    b match {
      case Block.BlockVar(id, annotatedTpe, annotatedCapt) => process(id)
      case Block.BlockLit(tparams, cparams, vparams, bparams, body) => process(body)
      case Block.Member(block, field, annotatedTpe) => process(block)
      case Block.Unbox(pure) => process(pure)
      case Block.New(impl) => process(impl)
    }

  def process(s: Stmt)(using defs: Map[Id, Definition]): Unit = s match {
    case Stmt.Scope(definitions, body) =>
      var currentDefs = defs
      definitions.foreach {
        case d: Definition.Def =>
          currentDefs += d.id -> d // recursive
          process(d)(using currentDefs)
        case d: Definition.Let =>
          process(d)(using currentDefs)
          currentDefs += d.id -> d // non-recursive
      }
      process(body)(using currentDefs)
    case Stmt.Return(expr) => process(expr)
    case Stmt.Val(id, binding, body) => process(binding); process(body)
    case Stmt.App(callee, targs, vargs, bargs) =>
      process(callee)
      vargs.foreach(process)
      bargs.foreach(process)
    case Stmt.If(cond, thn, els) => process(cond); process(thn); process(els)
    case Stmt.Match(scrutinee, clauses, default) =>
      process(scrutinee)
      clauses.foreach { case (id, value) => process(value) }
      default.foreach(process)
    case Stmt.Alloc(id, init, region, body) =>
      process(init)
      process(region)
      process(body)
    case Stmt.Var(id, init, capture, body) =>
      process(init)
      process(body)
    case Stmt.Get(id, capt, tpe) => process(id)
    case Stmt.Put(id, tpe, value) => process(id); process(value)
    case Stmt.Try(body, handlers) => process(body); handlers.foreach(process)
    case Stmt.Region(body) => process(body)
    case Stmt.Hole() => ()
  }

  def process(e: Expr)(using defs: Map[Id, Definition]): Unit = e match {
    case DirectApp(b, targs, vargs, bargs) =>
      process(b);
      vargs.foreach(process)
      bargs.foreach(process)
    case Run(s) => process(s)
    case Pure.ValueVar(id, annotatedType) => process(id)
    case Pure.Literal(value, annotatedType) => ()
    case Pure.PureApp(b, targs, vargs) => process(b); vargs.foreach(process)
    case Pure.Select(target, field, annotatedType) => process(target)
    case Pure.Box(b, annotatedCapture) => process(b)
  }

  def process(i: Implementation)(using defs: Map[Id, Definition]): Unit =
    i.operations.foreach { op => process(op.body) }

}

object Reachable {
  def apply(entrypoints: Set[Id], definitions: Map[Id, Definition]): Map[Id, Usage] = {
    val analysis = new Reachable(Map.empty, Nil, Set.empty)
    entrypoints.foreach(d => analysis.process(d)(using definitions))
    analysis.reachable
  }

  def apply(m: ModuleDecl): Map[Id, Usage] = {
    val analysis = new Reachable(Map.empty, Nil, Set.empty)
    val defs = m.definitions.map(d => d.id -> d).toMap
    m.definitions.foreach(d => analysis.process(d)(using defs))
    analysis.reachable
  }

  def apply(s: Stmt.Scope): Map[Id, Usage] = {
    val analysis = new Reachable(Map.empty, Nil, Set.empty)
    analysis.process(s)(using Map.empty)
    analysis.reachable
  }
}


enum Usage {
  case Once
  case Many
  case Recursive
}

