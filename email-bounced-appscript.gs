//google apps script https://script.google.com/u/5/home/projects/1Fm2LcnLIjdSwRYqdv2-vQ9hg-9ne0-qSnE4yNT1gFV9gGfhxoNm5ge7q/edit
//Search bounced emails in Trash

function extractEmails() {
  var startIndex = 0; // Change this value to start from a specific index
  Logger.log("started")
  var threads = GmailApp.search('in:trash');//anywhere
  Logger.log("got "+threads.length+" threads")
  // threads.sort(function(a, b) {
  //   return a.getId() - b.getId();
  // });
  var myEmail = Session.getActiveUser().getEmail();
  var index = 0;
  Logger.log(["index", "threadId","threadSnippet", "from", "to","errorTo", "subject", "type", "errorMsg", "time"].join('\t'));
  threads.forEach(function(thread) {
    var messages = thread.getMessages();
    messages.sort(function(a, b) {
      return a.getDate() - b.getDate();
    });
    messages.forEach(function(msg) {
      if (index < startIndex) {
        index++;
        return;
      }
      var subject = msg.getSubject();
      var from = msg.getFrom();
      var to = msg.getTo();
      var time = msg.getDate();
      var type = (subject.indexOf('Delivery Status Notification') > -1) ? 'error' :
                   (from.indexOf(myEmail) > -1) ? 'personal' : '';

      //** Address not found **
      //Your message wasn't delivered to sesizari.invatamants2@dgapi.ro because the address couldn't be found, or is unable to receive mail.
      //The response from the remote server was:
      //550 5.4.1 Recipient address rejected: Access denied. [DB3PEPF0000885C.eurprd02.prod.outlook.com 2025-01-24T13:29:15.015Z 08DD36FABB4C843C]
      var errorMsg = '-';
      var errorTo = '-';
      if (msg.getSubject().indexOf('Delivery Status Notification') > -1) {
        errorTo = messages[0].getTo();
        errorMsg = msg.getPlainBody();
      }

      Logger.log([index, thread.getId(), thread.getFirstMessageSubject(), from, to, errorTo, subject, type, errorMsg, time].join('\t'));
      index++;
    });
  });
}
