package silAST.expressions.terms
import silAST.AtomicNode
import silAST.symbols.ProgramVariable
import silAST.source.SourceLocation
import silAST.expressions.ProgramTermNode

class OldProgramVariableNode(
		sl : SourceLocation, 
		variable : ProgramVariable 
	) 
	extends ProgramVariableNode(sl, variable) 
{
	assert(variable!=null);
	
	override def toString(): String = { return "old(" + variable.name + ")"; }
}