package effekt
package cps

import effekt.PhaseResult
import effekt.Phase
import effekt.context.Context
import effekt.symbols.{Symbol}
import effekt.context.assertions.*
import effekt.util.messages.ErrorReporter
import effekt.core.CoreParsers.statement
import effekt.source.AnnotateCaptures.annotate
import scala.runtime.BoxedUnit






object Transformer extends Phase[PhaseResult.CoreLifted, PhaseResult.CpsTransformed] {

  val phaseName = "cps-transform"


  def run(input: CoreLifted)(using Context): Option[CpsTransformed] =
    val transformed = cps.transform(input.core)
    Some(CpsTransformed(input.source, input.tree, input.mod, input.core, transformed))
}

def transform(core: lifted.ModuleDecl): ModuleDecl = {
  ModuleDecl(core.path, core.includes, core.decls map transform, core.externs map transform, core.definitions map transform, core.exports)
}
def transform(decl: lifted.Declaration): Declaration = decl match {
  case lifted.Declaration.Data(id, tparams, constructors) => Declaration.Data(id, constructors map transform)
  case lifted.Declaration.Interface(id, tparams, properties) => Declaration.InterfaceDecl(id, properties map transform)
}

def transform(extern: lifted.Extern): Extern = extern match {
  case lifted.Extern.Def(id, tparams, params, ret, lifted.ExternBody.StringExternBody(_, body)) =>
    Extern.Def(id, params map transform, Template(body.strings, body.args map transform))
  case lifted.Extern.Include(_, contents) => Extern.Include(contents)
  case _ => Extern.Def(Id(""), List(), Template(List(), List()))
}

def transform(definition: lifted.Definition): Definition = 
  //println(definition)
  definition match {
  case lifted.Definition.Def(id, lifted.BlockLit(List(), params, body)) =>
    Definition.Function(id, params map transform, Id.apply("k"), transform(body))
  case lifted.Definition.Let(id, binding) => Definition.Let(id, transform(binding))
  case lifted.Definition.Def(id, b) => Definition.Let(id, transform(b))
}

def transform(arg: lifted.Argument): Expr = 
  arg match {
  case expr: lifted.Expr => transform(expr)
  case block: lifted.Block => transform(block)
  case lifted.Evidence(evList) => Evidence(evList map transform)
}

def transform(ev: lifted.Lift): cps.Lift = ev match {
  case lifted.Lift.Reg() => cps.Lift.Reg()
  case lifted.Lift.Try() => cps.Lift.Try()
  case lifted.Lift.Var(ev) => cps.Lift.Var(Id(ev.name.name))
}

def transform(expr: lifted.Expr): Expr = expr match {
  case lifted.Literal(value: Int, _) => Expr.Lit(value)
  case lifted.Literal(value: Long, _) => Expr.Lit(value.toInt)
  case lifted.Literal(value: String, _) => Expr.StringLit(value)
  case lifted.Literal(value: Boolean, _) => value match {
    case true => Expr.Lit(1)
    case false => Expr.Lit(0)
  }
  case lifted.Literal(value: BoxedUnit, _) => Expr.UnitLit()
  case lifted.Literal(value, _) => println(value.getClass()); ???//Expr.Lit(value.toInt); ???
  case lifted.ValueVar(id, annotatedType) => Expr.Var(id)
  case lifted.PureApp(b, targs, args) => Expr.PureApp(transform(b), (args map transform))
  case lifted.Make(data, tag, args) => Expr.Make(transform(data), tag, args map transform)
  case lifted.Select(target, field, annotatedType) => Expr.Select(transform(target), field)
  case lifted.Box(b) => Expr.Box(transform(b))
  case lifted.Run(s) => Expr.Run(transform(s))
}

def transform(b: lifted.Block): Expr = b match { // block => Term
  case lifted.Block.BlockVar(id, annotatedType) => Var(id)
  case lifted.Block.BlockLit(tparams, params, body) => BlockLit((params map transform) :+ Id("k"), transform(body))
  case lifted.Block.Member(b, field, annotatedType) => 
  b match {
    case lifted.Block.BlockVar(id, annotatedType) => annotatedType match {
      case lifted.BlockType.Interface(name, targs) => Member(transform(b), field, Interface(name, targs map transform))
      case _ => ???
    }
    case _ => ???
  }
  case lifted.Block.Unbox(e) => transform(e) 
  case lifted.Block.New(impl) => New(transform(impl))
  //case _ => ???
}

def transform(implementation: lifted.Implementation): Implementation =
  Implementation(Interface(implementation.interface.name, implementation.interface.targs map transform), implementation.operations map transform)

def transform(operation: lifted.Operation): Operation =
  Operation(operation.name, transform(operation.implementation))

def transform(blockLit: lifted.BlockLit): BlockLit = blockLit match {
  case lifted.BlockLit(_, params, body) => BlockLit((params map transform) :+ Id("k"), transform(body))
}

def transform(stmt: lifted.Stmt): Term = stmt match {
  case lifted.Stmt.Return(e)  =>  AppCont(Id("k"), transform(e))
  case lifted.Stmt.Val(id, binding, body)  => LetCont(Id("k"), id, transform(body), transform(binding))
  
  case lifted.Stmt.Scope(definitions, body) => transform(definitions, transform(body))
  case lifted.Stmt.App(b, targs, args) => App(transform(b), args map transform, Id("k"))
  
  case lifted.Stmt.If(cond, thn, els) => If(transform(cond), transform(thn), transform(els))
  case lifted.Stmt.Match(scrutinee, clauses, Some(default)) => Match(transform(scrutinee), clauses map ((id: lifted.Id, blockLit: lifted.BlockLit) => (Id(id.name.name), transform(blockLit))), Some(transform(default)))
  case lifted.Stmt.Match(scrutinee, clauses, None) => Match(transform(scrutinee), clauses map ((id: lifted.Id, blockLit: lifted.BlockLit) => (Id(id.name.name), transform(blockLit))), None)

  //nicht
  case lifted.Stmt.Region(body) => ???
  case lifted.Stmt.Alloc(id, init, region, ev, body) => ???
  
  case lifted.Stmt.Var(init, body) => StateVar(transform(init), transform(body))
  case lifted.Stmt.Get(id, lifted.Evidence(lifts), annotatedTpe) => Get(id, Evidence(lifts map transform))
  case lifted.Stmt.Put(id, lifted.Evidence(lifts), value) => Put(id, Evidence(lifts map transform), transform(value))

  case lifted.Stmt.Try(lifted.BlockLit(_, params, body), handlers) => params match {
    case ev :: caps => Reset(transform(ev), transform(caps, handlers, body)) 
    case _ => ???
  }
  case lifted.Stmt.Try(body, handler) => ???

  case lifted.Stmt.Reset(body) => Reset(Id("evidence"), transform(body))

  case lifted.Stmt.Shift(lifted.Evidence(List(lifted.Lift.Var(ev))), lifted.BlockLit(Nil, List(param), body)) => Shift(transform(ev), transform(param), transform(body)) //shift zu cps

  case lifted.Stmt.Hole() => ???

  case _ => ???
}

//assuming caps and handlers have the same size
def transform(caps: List[lifted.Param], handlers: List[lifted.Implementation], body: lifted.Stmt): Term = caps match {
  case Nil => transform(body)
  case head :: next => Let(transform(head), New(transform(handlers.head)), transform(next, handlers.tail, body))
}

def transform(definitions: List[lifted.Definition], rest: Term): Term = definitions match {
  case Nil => rest
  case lifted.Definition.Let(id, binding) :: tail => Term.Let(id, transform(binding), transform(tail, rest))
  case lifted.Definition.Def(id, block) :: tail => Term.Let(id, transform(block), transform(tail, rest))
}

def transform(constructor: lifted.Constructor): Constructor = Constructor(constructor.id, constructor.fields map transform)
def transform(property: lifted.Property): Property = property match {
  case lifted.Property(id, lifted.BlockType.Interface(name, targs)) => Property(name, targs map transform)
  case lifted.Property(id, lifted.BlockType.Function(_, eparams, vparams, _, result)) => Property(id, vparams map transform)
}
def transform(field: lifted.Field): Id = field match {
  case lifted.Field(id, tpe) => id
}

def transform(tpe: lifted.ValueType): Id = tpe match {
  case lifted.ValueType.Var(id)  => id
  case lifted.ValueType.Data(name, targs)  => name
  case lifted.ValueType.Boxed(blockTpe)  => transform(blockTpe)
}

def transform(tpe: lifted.BlockType): Id = tpe match {
  case lifted.BlockType.Function(_, _, _, _, result) => transform(result)
  case lifted.BlockType.Interface(name, _) => name
}

def transform(id: lifted.Id): Id = Id(id.name.name)

def transform(param: lifted.Param): Id = param.id
