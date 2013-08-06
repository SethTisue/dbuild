package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import org.eclipse.core.runtime.SubProgressMonitor
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._

// Note: when implementing send(), only use templ.long, templ.short, and templ.summary (and templ.id for diagnostics only).
// Do not add any text to them, before or after: the entire text must be definable using the templates only.

class ConsoleNotificationContext(log: Logger) extends NotificationContext[ConsoleNotification] {
  def defaultOptions = ConsoleNotification()
  def send(n: ConsoleNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    templ.long.split("\n").foreach(log.info(_))
    None
  }
}

class EmailNotificationContext(log: Logger) extends NotificationContext[EmailNotification] {
  def defaultOptions = EmailNotification()
  def send(n: EmailNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    val props  = new Properties();
    props.put("mail.smtp.host", "my-mail-server")
    val session = Session.getInstance(props, null)
    log.info("Sending to " + n.to + " the outcome of project " + outcome.project + " using template " + templ.id)
    try {
        val msg = new MimeMessage(session)
        msg.setFrom(new InternetAddress("me@example.com"))
        msg.setRecipients(Message.RecipientType.TO,
                          "you@example.com");
        msg.setSubject("JavaMail hello world example")
        msg.setSentDate(new Date())
        msg.setText("Hello, world!\n");
        Transport.send(msg, Array(new InternetAddress("me@example.com"), new InternetAddress("my-password")))
    } catch {
        case mex:MessagingException =>
        log.error("ERROR SENDING to " + n.to + " the outcome of project " + outcome.project + " using template " + templ.id)
        log.error(mex.toString)
    }
    None
  }
}

class Notifications(build: DistributedBuildConfig, log: Logger) {
  val definedTemplates = build.notificationOptions.templates
  val definedNotifications =
    (build.notificationOptions.notifications ++ build.projects.flatMap { _.notifications })
  val usedNotificationKindIds = definedNotifications.map { _.kind }.distinct
  val allContexts = Map("console" -> new ConsoleNotificationContext(log),
    "email" -> new EmailNotificationContext(log))
  val unknown = usedNotificationKindIds.toSet -- allContexts.keySet
  if (unknown.nonEmpty) {
    sys.error(unknown.mkString("These notification kinds are unknown: ", ",", ""))
  }
  def sendNotifications(rootOutcome: BuildOutcome) = {
    val outcomes = rootOutcome.outcomes // children of the dbuild root
    usedNotificationKindIds foreach { allContexts(_).before }
    def processOneNotification(n: Notification, outcome: BuildOutcome) = {
      val resolvedTempl = n.resolveTemplate(outcome, definedTemplates)
      if (outcome.whenIDs.intersect(n.when).nonEmpty) {
        val formatter=new TemplateFormatter(resolvedTempl, outcome)
        allContexts(n.kind).notify(n.send, formatter, outcome) map (log.warn(_))
      }
    }
    // send per-project notifications:
    build.projects.foreach { p =>
      val projectOutcomes = outcomes.filter(_.project == p.name)
      if (projectOutcomes.isEmpty)
        sys.error("Internal error: no outcome detected for project " + p.name + ". Please report.")
      if (projectOutcomes.length > 1)
        sys.error("Internal error: multiple outcomes detected for project " + p.name + ". Please report.")
      p.notifications.foreach(processOneNotification(_, projectOutcomes.head))
    }
    // send the global notifications:
    build.notificationOptions.notifications.foreach(processOneNotification(_, rootOutcome))
    usedNotificationKindIds foreach { allContexts(_).after }
  }
}
