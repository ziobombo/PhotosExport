package it.fraguglia.PhotosExport

import picocli.CommandLine

/**
 * @author ${user.name}
 */
object App {
  def main(args: Array[String]) {
    CommandLine.run(new MyApp(), args: _*)
  }
}
