package tecnico.ssoft.phpsat.core;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import tecnico.ssoft.phpsat.parser.CodeParser;
import tecnico.ssoft.phpsat.parser.PHPGrammarLexer;
import tecnico.ssoft.phpsat.parser.PHPGrammarParser;
import tecnico.ssoft.phpsat.parser.VulnerabilitiesParser;
import tecnico.ssoft.phpsat.parser.ast.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Analyser
{
    private String result;
    private List<Node> program;
    private List<Vulnerability> vulnerabilities;

    public Analyser(String file)
            throws IOException
    {
        result = "";

        VulnerabilitiesParser vulnerabilitiesParser = new VulnerabilitiesParser();
        vulnerabilitiesParser.parse();
        vulnerabilities = vulnerabilitiesParser.result();

        FileInputStream inputStream = new FileInputStream(file);
        ANTLRInputStream input = new ANTLRInputStream(inputStream);

        PHPGrammarLexer lexer = new PHPGrammarLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PHPGrammarParser parser = new PHPGrammarParser(tokens);
        ParseTree tree = parser.prog();

        ParseTreeWalker walker = new ParseTreeWalker();
        CodeParser listener = new CodeParser();
        walker.walk(listener, tree);

        program = listener.result();
    }

    public void analyse()
    {
        analyseVulnerabilities();
    }

    public String result()
    {
        if (result.isEmpty()) {
            return "This program is safe.";
        }
        return result.replace("\n", "");
    }

    private void analyseVulnerabilities()
    {
        for (Vulnerability vulnerability : vulnerabilities) {
            taintAllVariables(program); // start with everything tainted

            for (Node node : program) {
                if (node instanceof Assignment) {
                    Assignment assignment = (Assignment) node;
                    RightValue rightValue = assignment.getRight();
                    if (rightValue instanceof Variable) {
                        Variable variable = (Variable) rightValue;
                        if (variable.isEntryPoint(vulnerability.getEntryPoints())) {
                            variable.setEntryPoint(true);
                            variable.setEntryPointName(variable.getEntryPointName());
                            assignment.getLeft().setEntryPoint(true);
                            assignment.getLeft().setEntryPointName(variable.getEntryPointName());
                        }
                        else if (variable.isTainted()) {
                            assignment.getLeft().taint();
                        }
                        else {
                            assignment.getLeft().untaint();
                        }
                    }
                    else if (rightValue instanceof Function) {
                        Function function = (Function) rightValue;
                        analyseFunction(function, vulnerability, assignment);
                    }
                    else if (rightValue instanceof Value) {
                        Value value = (Value) rightValue;
                        value.untaint();
                        for (Variable variable : value.getVariables()) {
                            if (variable.isTainted() && variable.isEntryPoint(vulnerability.getEntryPoints())) {
                                value.taint();
                                assignment.getLeft().taint();
                                assignment.getLeft().setEntryPoint(true);
                                assignment.getLeft().setEntryPointName(variable.getEntryPointName());
                                break;
                            }
                        }
                        if (value.isTainted()) {
                            assignment.getLeft().taint();
                        }
                        else {
                            assignment.getLeft().untaint();
                        }
                    }
                }
                else if (node instanceof Function) {
                    Function function = (Function) node;
                    analyseFunction(function, vulnerability, null);
                }
            }
        }
    }

    private void taintAllVariables(List<Node> code)
    {
        for (Node node : program) {
            if (node instanceof Assignment) {
                Assignment assignment = (Assignment) node;
                assignment.getLeft().taint();
                if (assignment.getRight() instanceof Variable) {
                    Variable variable = (Variable) assignment.getRight();
                    variable.taint();
                }
                else if (assignment.getRight() instanceof Value) {
                    Value value = (Value) assignment.getRight();
                    for (Variable variable : value.getVariables()) {
                        variable.taint();
                    }
                }
            }
            else if (node instanceof Variable) {
                Variable variable = (Variable) node;
                variable.taint();
            }
            else if (node instanceof Value) {
                Value value = (Value) node;
                for (Variable variable : value.getVariables()) {
                    variable.taint();
                }
            }
        }
    }

    private void analyseFunction(Function function, Vulnerability vulnerability, Assignment assignment)
    {
        List<String> args = new ArrayList<>();

        if (function.isThisFunction(vulnerability.getSanitizationFunctions())) {
            for (RightValue rv : function.getArgs()) {
                rv.untaint();
            }
            function.untaint();
            if (assignment != null) {
                assignment.getLeft().untaint();
            }
        }
        else if (function.isThisFunction(vulnerability.getSinks())) {
            function.untaint();
            if (assignment != null) {
                assignment.getLeft().untaint();
            }
            for (RightValue rv : function.getArgs()) {
                if (rv instanceof Variable) {
                    Variable variable = (Variable) rv;
                    if (variable.isEntryPoint(vulnerability.getEntryPoints()) && variable.isTainted()) {
                        function.taint();
                        args.add(variable.getName());
                        if (assignment != null) {
                            assignment.getLeft().taint();
                        }
                    }
                }
            }
        }

        if (!args.isEmpty()) {
            addVulnerability(function, vulnerability, args);
        }
    }

    private void addVulnerability(Function function, Vulnerability vulnerability, List<String> args)
    {
        result += "The code is vulnerable to " + vulnerability.type() + " on function " +
                function.getName() + " because of the args: ";

        for (String arg : args) {
            result += arg + ", ";
        }

        result = result.substring(0, result.length() - 2);

        result += ".\n";
    }

    /*
    private void printCode()
    {
        System.out.println("START\n");
        for (Node node : program) {
            if (node instanceof Assignment) {
                System.out.println("START ASSIGNMENT");
                Assignment assignment = (Assignment) node;
                System.out.println(assignment.getLeft().toString());
                if (assignment.getRight() instanceof Variable) {
                    Variable variable = (Variable) assignment.getRight();
                    System.out.println(variable);
                }
                else if (assignment.getRight() instanceof Value) {
                    Value value = (Value) assignment.getRight();
                    for (Variable variable : value.getVariables()) {
                        System.out.println(value.toString());
                    }
                }
                else if (assignment.getRight() instanceof Function) {
                    Function function = (Function) assignment.getRight();
                    System.out.println(function);
                }
                System.out.println("END ASSIGNMENT\n");
            }
            else if (node instanceof Variable) {
                System.out.println("START VARIABLE");
                Variable variable = (Variable) node;
                System.out.println(variable);
                System.out.println("END VARIABLE\n");
            }
            else if (node instanceof Value) {
                System.out.println("START VALUE");
                Value value = (Value) node;
                for (Variable variable : value.getVariables()) {
                    System.out.println(variable);
                }
                System.out.println("END VALUE\n");
            }
            else if (node instanceof Function) {
                System.out.println("START FUNCTION");
                Function function = (Function) node;
                System.out.println(function);
                System.out.println("END FUNCTION\n");
            }
        }

        System.out.println("\nEND");
    }
    */

}