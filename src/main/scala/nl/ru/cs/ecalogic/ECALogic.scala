/*
 * ecalogic: a tool for performing energy consumption analysis.
 *
 * Copyright (c) 2013, J. Neutelings, D. Peelen, M. Schoolderman
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *   Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 *
 *   Neither the name of the Radboud University Nijmegen nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package nl.ru.cs.ecalogic

import parser.Parser
import analysis.{SemanticAnalysis, EnergyAnalysis}
import util.{ErrorHandler, DefaultErrorHandler}
import config.Options

import scala.io.Source

import java.io.File
import java.io.FileNotFoundException
import java.lang.NumberFormatException

import model._
import ast.Import

object ECALogic {

  var forceComponents = Map.empty[String,ComponentModel]

  val defaultErrorHandler = new DefaultErrorHandler

  def report(fileName: String, state: (GlobalState, GlobalState)) {
    println("Lower bound:")
    state._1.transform((_,st)=>st.energy) match {
      case result if Options.terse =>
        println(result)
      case (states, t) =>
        println(s"$fileName:")
        println(f"Time:\t$t%s")
        println(f"Energy:\t${states.values.reduce(_+_)}%s")
        for((name, e) <- states)
          println(f"└ $name%13s\t$e%s")
    }
    println("Upper bound:")
    state._2.transform((_,st)=>st.energy) match {
      case result if Options.terse =>
        println(result)
      case (states, t) =>
        println(s"$fileName:")
        println(f"Time:\t$t%s")
        println(f"Energy:\t${states.values.reduce(_+_)}%s")
        for((name, e) <- states)
          println(f"└ $name%13s\t$e%s")
    }
  }

  def analyse(fileName: String) = {
    val file = new File(fileName).getAbsoluteFile
    val source = defaultErrorHandler.report(Source.fromFile(file).mkString)
    val errorHandler = new DefaultErrorHandler(sourceText = Some(source), sourceURI = Some(file.toURI))

    val program = errorHandler.reportAll("One or more errors occurred during parsing.") {
      val parser = new Parser(source, errorHandler)
      parser.program()
    }

    val components = errorHandler.reportAll("One or more errors occurred while loading components.") {
      ComponentModel.fromImports(program.imports)
    } ++ forceComponents

    errorHandler.reportAll("One or more errors occurred during semantic analysis.") {
      val checker = new SemanticAnalysis(program, components, errorHandler)
      checker.functionCallHygiene()
      checker.variableReferenceHygiene()
    }

    errorHandler.reportAll("One or more errors occurred during energy analysis.") {
      val consumptionAnalyser = new EnergyAnalysis(program, components, errorHandler)
      consumptionAnalyser.analyse(Options.entryPoint)
    }
  }

  /* if arg is of the form name=file.ext, return (name, file.ext);
     otherwise return (file, file.ext)
   */
  def getAlias(arg: String): (String,String) = {
    val splitPos = arg.indexOf('=')
    if(splitPos >= 0) 
      arg.substring(0, splitPos) -> arg.substring(splitPos+1)
    else 
      "" -> arg
  }

  def complain(msg: String) {
    Console.err.println(msg)
    throw new ECAException(msg)
  }

  def main_(args: Array[String]): Int = try {
    var idle = true
    val fileArgs = config.Options(args)

    config.Options.aliasOverrides.foreach {
      case bindSpec =>
        val (alias, trueClassName) = getAlias(bindSpec)
        val classPath = trueClassName.split('.')
        val model = ComponentModel.fromImport(Import(classPath, if(alias.isEmpty) classPath.last else alias))
        forceComponents = forceComponents + (alias->model)
    }
    fileArgs.foreach {
      case fileName if fileName.endsWith(".ecm") =>
        val (alias, trueFileName) = getAlias(fileName)
        val file  = new File(trueFileName).getAbsoluteFile
        val name  = if(alias.isEmpty) file.getName.substring(0,file.getName.length-4) else alias
        val model = ECMModel.fromFile(file)
        forceComponents = forceComponents + (name->model)
      case _ =>
        /* we will complain later :) */
    }
    fileArgs.foreach {
      case fileName if fileName.endsWith(".eca") =>
        val state = analyse(fileName)
        report(fileName, state)
        idle = false
      case fileName if fileName.endsWith(".ecm") =>
        /* already handled */
      case fileName =>
        complain(s"File not recognized: $fileName")
    }
    if(idle)
      Console.err.println("Nothing to do! Run with --help to see usage instructions.")

    0
  } catch {
    case _: ECAException          =>
      Console.err.println("Aborted.")
      1
    case e: NumberFormatException =>
      Console.err.println(s"Numeric argument expected: ${e.getMessage}")
      1
    case e: FileNotFoundException =>
      Console.err.println(s"${e.getMessage}")
      1
    case e: Exception =>
      Console.err.println("Oops. An exception seems to have escaped.")
      e.printStackTrace()
      2
  }

  def main(args: Array[String]) {
    sys.exit(main_(args))
  }

}

