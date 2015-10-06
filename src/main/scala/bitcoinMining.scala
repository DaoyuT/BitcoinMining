import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorSelection
import java.net.InetAddress
import com.typesafe.config.ConfigFactory
import akka.routing.RoundRobinRouter
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.List
import java.util.ArrayList
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import akka.routing.RoundRobinPool
import scala.concurrent.duration.Duration

case object Start
case class AssignWorkToClient(msg: String)
case class WorkFromServer(k: Int, clientId: Int, nrOfDigits: Int)
case class StartMining(k: Int, clientId: Int, workId: Int, nrOfDigits: Int)
case class CoinsToClient(coins: List[String])
case class CoinsToServer(coins: List[String])
case object Stop

object Project1 {


  def checkInput(args: Array[String]): Boolean = {

    var isValid: Boolean = true
    var errorMsg: String = ""
    var k: Int = 0

    if(args.length < 2){
      isValid = false
      errorMsg = "Not enough input arguments."
    } else {
      var mode: String = args(0)
      if(mode != "-s" && mode != "-c"){
        isValid = false
        errorMsg = "Mode error."
      } else {
        if(mode == "-c"){
          if (!isValidIP(args(1))) {
            isValid = false
            errorMsg = "IP address is not valid."
          }
        }
        if(mode == "-s"){
          try{
            k = args(1).toInt
            if(k < 1 || k > 64){
              isValid = false
              errorMsg = "K(# of leading 0s in the coin) should be 1 <= k <= 63."
            }
          } catch {
            case ex: NumberFormatException => {
              isValid = false
              errorMsg = "K(# of leading 0s in the coin) should be 1 <= k <= 63."
            }
          }
          if(args.length == 3){
            try{
            var d = args(2).toInt
            if(d < 1 || d > 12){
                isValid = false
                errorMsg = "D(10^d random strings will be tried on each work) should be 1 <= d <= 10."
              }
            } catch {
              case ex: NumberFormatException => {
                isValid = false
                errorMsg = "D(10^d random strings will be tried on each work) should be 1 <= d <= 10."
              }
            }
          }     
        }
      }
    }

    if(isValid == false){
      println(errorMsg)
      System.exit(0);
    }

    def isValidIP(args: String): Boolean = {

      val pattern = Pattern.compile("^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$")
      val matcher = pattern.matcher(args)

      return matcher.matches()
    }

    return isValid
  }

  def main(args: Array[String]) {


    checkInput(args)

    if (args(0) == "-s") {

      var k: Int = args(1).toInt
      var serverCustomConfigString: String = null
      try {
        val hostAddress: String = InetAddress.getLocalHost().getHostAddress()
        serverCustomConfigString = """
akka {
  actor {
    provider = "akka.remote.RemoteActorRefProvider"
  }
  remote {
    netty.tcp {
      hostname = """" + hostAddress + """"
      port = 1111
    }
  }
}
"""
      } catch {
        case ex: Exception => {
          println("Can not gey the local IP address.")
        }
      }

      var localSystem: ActorSystem = null
      val serverCustomConf = ConfigFactory.parseString(serverCustomConfigString)
      localSystem = ActorSystem("LocalSystem", ConfigFactory.load(serverCustomConf))

      var nrOfDigits = 6
      try {
        if (args.size == 3) {
          nrOfDigits = args(2).toInt
        }
      } catch {
        case ex: Exception => {
          nrOfDigits = 6
        }
      }
      
      val server = localSystem.actorOf(Props(classOf[Server], k, nrOfDigits), name = "server")

    } else {

      var ip: String = args(1)
      val serverPath = "akka.tcp://LocalSystem@" + ip + ":1111/user/server"

      val clientCustomConfigString: String = """
   akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
    }
    log-dead-letters = off
  }
  """
      val clientCustomConf = ConfigFactory.parseString(clientCustomConfigString)
      val remoteSystem = ActorSystem("RemoteSystem", clientCustomConf)

      val remoteClient = remoteSystem.actorOf(Props(classOf[Client], serverPath), name = "remote_client")
    }
  }

  class Server(k: Int, nrOfDigits: Int) extends Actor {
    val client = context.actorOf(Props(classOf[Client], ""), name = "local_client")

    client ! Start

    var nrOfClients: Int = 0
    var totalCoins: Int = 0
    var received: Int = 0
    val startTime: Long = System.currentTimeMillis

    def receive = {
      case AssignWorkToClient(msg) => {
        println(msg)
        sender ! WorkFromServer(k, nrOfClients, nrOfDigits)
        nrOfClients = nrOfClients + 1
      }

      case CoinsToServer(coins) => {
        val numCoinsFromClient = coins.size()
        if (numCoinsFromClient > 0) {
          for (i <- 0 to numCoinsFromClient - 1)
            println(coins.get(i))
          totalCoins = totalCoins + numCoinsFromClient
        }
        received = received + 1
        if(nrOfClients == received){

          var duration: Double = (System.currentTimeMillis - startTime).toDouble / 1000
          println(totalCoins + " coins has been found.\t" + nrOfClients + " client(s) has involved.\tTotal time cost: " + duration + " s.")
          self ! Stop
        }

      }

      case Stop => {
        context.system.shutdown()
      }
    }
  }

  class Client(serverPath: String) extends Actor {

    var serverRefForRemote: ActorSelection = _
    if (serverPath.length() > 0) {
      serverRefForRemote = context.actorSelection(serverPath)
      serverRefForRemote ! AssignWorkToClient("Remote Client: " + self)
    }

    var server: ActorRef = _
    var factor: Double = 1.5
    val nrOfWorkers: Int = Math.ceil(Runtime.getRuntime().availableProcessors() * factor).toInt

    val workerRouter = context.actorOf(RoundRobinPool(nrOfWorkers).props(Props[Worker]), "workerRouter")

    var j: Int = 0
    var allWorkerCoins: List[String] = new ArrayList[String]()

    def receive = {
      case Start => {
        sender ! AssignWorkToClient("Local Client: " + self)
      }

      case WorkFromServer(k, clientId, nrOfDigits) => {
        server = sender
        var i: Int = 0
        while (i < (nrOfWorkers)) {
          workerRouter ! StartMining(k, clientId, i, nrOfDigits)
          i = i + 1
        }
      }

      case CoinsToClient(coins) => {
        j = j + 1
        if (coins.size() > 0) {
          allWorkerCoins.addAll(coins)
        }

        if (j == (nrOfWorkers)) {
          server ! CoinsToServer(allWorkerCoins)

          j = 0
          allWorkerCoins = new ArrayList[String]()
        }
      }
    }
  }

  class Worker extends Actor {
    def receive = {
      case StartMining(k, clientId, workId, nrOfDigits) =>
        sender ! CoinsToClient(mineBitCoints(k, clientId, workId, nrOfDigits))
    }

    //Generate SHA-256 String and varify, return List of valid Strings
    def mineBitCoints(k: Int, clientId: Int, workId: Int, nrOfDigits: Int): List[String] = {

      def generateSeedAndValidate(start: Int, length: Int, testStr: String, prefix: String, sb: StringBuilder, list: List[String]){
        if(start > length){
          return
        } 
        for(ch <- 'a' to 'j'){
          sb.setCharAt(start, ch)
          if(start == length){
            var seed = prefix + sb.substring(1, length + 1)
            var md = MessageDigest.getInstance("SHA-256")
            md.update(seed.getBytes("UTF-8"))
            var digest = md.digest()
            var digestHexRep = bytes2hex(digest)
            if (digestHexRep <= testStr) {
              list.add(seed + "\t" + digestHexRep)
            }
          }
          generateSeedAndValidate(start + 1, length, testStr, prefix, sb, list);
        }
      }

      val gatorLink: String = "dytu0316|"
      val id: String = String.format("%3s", clientId.toString)
      val prefix = gatorLink + id.replaceAll("\\s", "0") + "." + workId.toString

      var bound: StringBuilder = new StringBuilder(64)
      for (i <- 1 to k) {
        bound.append(0)
      }
      for (i <- (k + 1) to 64) {
        bound.append('f')
      }
      val hexToCmp: String = bound.toString()

      var coinsGenerated = new ArrayList[String]()
      var sb: StringBuilder = new StringBuilder("~~~~~~~~~~~~~~~")
      generateSeedAndValidate(1, nrOfDigits, hexToCmp, prefix, sb, coinsGenerated)

      return coinsGenerated
    }

    //Array of bytes to hex String
    def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String = {
      sep match {
        case None => bytes.map("%02x".format(_)).mkString
        case _ => bytes.map("%02x".format(_)).mkString(sep.get)
      }
    }

  }
}