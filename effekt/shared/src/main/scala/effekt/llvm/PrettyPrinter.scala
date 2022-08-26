package effekt.llvm

import effekt.context.Context

type LLVMFragment = String

// indent all but the first line with four spaces
def indentedLines(text: String): String = text.split("\n").map("    " + _).mkString("\n").drop(4)

def commaSeparated(args: List[String]): String = args.mkString(", ")
def spaceSeparated(args: List[String]): String = args.mkString(" ")

object PrettyPrinter {

  def asFragment(definitions: List[Definition])(implicit C: Context): LLVMFragment =
    definitions.map(asFragment).mkString("\n\n")

  def asFragment(definition: Definition)(implicit C: Context): LLVMFragment = definition match {
    case Function(returnType, name, parameters, basicBlocks) =>
      s"""
define fastcc ${asFragment(returnType)} ${globalName(name)}(${commaSeparated(parameters.map(asFragment))}) {
    ${indentedLines(basicBlocks.map(asFragment).mkString("\n\n"))}
}
"""
    case VerbatimFunction(returnType, name, parameters, body) =>
      // TODO what about calling convention?
      s"""
define ${asFragment(returnType)} ${globalName(name)}(${commaSeparated(parameters.map(asFragment))}) {
    $body
}
"""
    case Verbatim(content) => content
  }

  def asFragment(basicBlock: BasicBlock)(implicit C: Context): LLVMFragment = basicBlock match {
    case BasicBlock(name, instructions, terminator) =>
      s"""
${name}:
${indentedLines(instructions.map(asFragment).mkString("\n"))}
    ${asFragment(terminator)}
"""
  }

  def asFragment(instruction: Instruction)(implicit C: Context): LLVMFragment = instruction match {

    case Call(_, VoidType(), ConstantGlobal(_, name), arguments) =>
       s"call void ${globalName(name)}(${commaSeparated(arguments.map(asFragment))})"
    case Call(result, tpe, ConstantGlobal(_, name), arguments) =>
       s"${localName(result)} = call ${asFragment(tpe)} ${globalName(name)}(${commaSeparated(arguments.map(asFragment))})"
    case Call(_, _, nonglobal, _) => C.abort(s"cannot call non-global operand: $nonglobal")

    case TailCall(LocalReference(_, name), arguments) =>
      s"tail call fastcc void ${localName(name)}(${commaSeparated(arguments.map(asFragment))})"
    case TailCall(ConstantGlobal(_, name), arguments) =>
      s"tail call fastcc void ${globalName(name)}(${commaSeparated(arguments.map(asFragment))})"
    case TailCall(nonglobal, _) => C.abort(s"can only tail call references, not: $nonglobal")
    // TODO [jfrech, 2022-07-26] Why does tail call even have a return type if we do not use it?

    case Load(result, LocalReference(PointerType(tpe), name)) =>
      s"${localName(result)} = load ${asFragment(tpe)}, ${asFragment(LocalReference(PointerType(tpe), name))}"
    case Load(_, operand) => C.abort(s"WIP: loading anything but local references not yet implemented: $operand")
    // TODO [jfrech, 2022-07-26] Why does `Load` explicitly check for a local reference and `Store` does not?
    case Store(address, value) =>
      s"store ${asFragment(value)}, ${asFragment(address)}"

    case GetElementPtr(result, LocalReference(PointerType(tpe), name), List(i0)) =>
      s"${localName(result)} = getelementptr ${asFragment(tpe)}, ${asFragment(LocalReference(PointerType(tpe), name))}, i64 $i0"
    case GetElementPtr(result, LocalReference(PointerType(tpe), name), List(i0, i1)) =>
      s"${localName(result)} = getelementptr ${asFragment(tpe)}, ${asFragment(LocalReference(PointerType(tpe), name))}, i64 $i0, i32 $i1"
    case GetElementPtr(_, operand, _) => C.abort(s"can only form a pointer to a local reference, not: $operand")

    case BitCast(result, operand, tpe) =>
      s"${localName(result)} = bitcast ${asFragment(operand)} to ${asFragment(tpe)}"

    case Add(result, operand0, ConstantInt(n)) =>
      s"${localName(result)} = add ${asFragment(operand0)}, $n"
    case Add(_, _, operand1) => C.abort(s"WIP: currently only right-constant additions are supported, not: $operand1")

    case InsertValue(result, aggregate, element, index) =>
      s"${localName(result)} = insertvalue ${asFragment(aggregate)}, ${asFragment(element)}, $index"

    case ExtractValue(result, aggregate, index) =>
      s"${localName(result)} = extractvalue ${asFragment(aggregate)}, $index"
  }

  def asFragment(terminator: Terminator): LLVMFragment = terminator match {
    case RetVoid() =>
      s"ret void"
    case Switch(operand, defaultDest, dests) =>
      def destAsFragment(dest: (Int, String)) = s"i64 ${dest._1}, label ${localName(dest._2)}";
      s"switch ${asFragment(operand)}, label ${localName(defaultDest)} [${spaceSeparated(dests.map(destAsFragment))}]"
    case CondBr(condition, trueDest, falseDest) =>
      s"br ${asFragment(condition)}, label ${localName(trueDest)}, label ${localName(falseDest)}"
  }

  def asFragment(operand: Operand): LLVMFragment = operand match {
    case LocalReference(tpe, name) => s"${asFragment(tpe)} ${localName(name)}"
    case ConstantGlobal(tpe, name) => s"${asFragment(tpe)} ${globalName(name)}"
    case ConstantInt(n) => s"i64 $n"
    case ConstantAggregateZero(tpe) => s"${asFragment(tpe)} zeroinitializer"
    case ConstantNull(tpe) => s"${asFragment(tpe)} null"
  }

  def asFragment(tpe: Type): LLVMFragment = tpe match {
    case VoidType() => "void"
    case IntegerType64() => "i64"
    case IntegerType8() => "i8" // required for `void*` (which only exists as `i8*` in LLVM)
    case IntegerType1() => "i1"
    case NamedType(name) => localName(name)
    case PointerType(referentType) => s"${asFragment(referentType)}*"
    case StructureType(elementTypes) => s"{${commaSeparated(elementTypes.map(asFragment))}}"
    case FunctionType(returnType, argumentTypes) => s"${asFragment(returnType)} (${commaSeparated(argumentTypes.map(asFragment))})"
  }

  def asFragment(parameter: Parameter): LLVMFragment = parameter match {
    case Parameter(tpe, name) => s"${asFragment(tpe)} ${localName(name)}"
  }

  def localName(name: String): LLVMFragment = "%" + name
  def globalName(name: String): LLVMFragment = "@" + name
}
