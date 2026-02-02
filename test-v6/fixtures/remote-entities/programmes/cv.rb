require 'json'

j = ARGV[0].then { File.read it }.then { JSON.parse it }

if cs = j['consumers']
  j.delete('consumers')
  c = cs.detect { it["consumerKey"] == 'rio' }
  c.delete('consentParticipationSTAP')
  j['consumer'] = c
end

if j['programType']
  j['programmeType'] = j.delete('programType')
end

c = j['consumer']

if es = j['educationSpecification']
  j.delete('educationSpecification')
  c['specificationId'] = es
end

if j['programId']
  j['programmeId'] = j.delete('programId')
end

if j['programmeType'] == 'specification'
  unless c['specificationType']
    if c['type']
      c['specificationType'] = c.delete('type')
    else
      STDERR.puts "Missing consumer.specificationType for #{ARGV[0]}"
      exit
    end
  end
elsif j['programmeType'].nil?
  STDERR.puts "Missing programmeType for #{ARGV[0]}"
  exit
end

# ensure programmes with programmeType specification have consumer.specificationType, formerly educationSpecificationType
# what to do with educationSpecificationSubtype? we have consumer.variantOf now

puts j.to_json
